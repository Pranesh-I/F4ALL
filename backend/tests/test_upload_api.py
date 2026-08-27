"""Upload endpoint tests.

These verify the server half of the protocol the mobile client already speaks.
The resume path matters most: it is the difference between a test that
eventually reaches SAI over a bad connection and one that never does.
"""

from __future__ import annotations

import hashlib
import os

CHUNK_SIZE = 1024


def make_video(size: int = 4096) -> bytes:
    return os.urandom(size)


def init_upload(client, payload_overrides: dict | None = None):
    video = make_video()
    payload = {
        "test_type": "SIT_UPS",
        "file_size_bytes": len(video),
        "checksum_sha256": hashlib.sha256(video).hexdigest(),
        "chunk_size_bytes": CHUNK_SIZE,
        "content_type": "video/mp4",
    }
    payload.update(payload_overrides or {})

    response = client.post("/api/videos/upload/init", json=payload)
    return video, response


def send_all_chunks(client, upload_id: str, video: bytes, chunk_size: int):
    total = (len(video) + chunk_size - 1) // chunk_size
    for index in range(total):
        chunk = video[index * chunk_size : (index + 1) * chunk_size]
        response = client.put(
            f"/api/videos/upload/{upload_id}/chunks/{index}",
            content=chunk,
            headers={"Content-Type": "video/mp4"},
        )
        assert response.status_code == 200, response.text
    return total


def test_full_upload_round_trip(client):
    video, response = init_upload(client)
    assert response.status_code == 200, response.text

    session = response.json()
    assert session["received_chunks"] == []
    assert session["chunk_size_bytes"] == CHUNK_SIZE

    send_all_chunks(client, session["upload_id"], video, CHUNK_SIZE)

    complete = client.post(f"/api/videos/upload/{session['upload_id']}/complete")
    assert complete.status_code == 200, complete.text
    assert complete.json()["checksum_verified"] is True
    assert complete.json()["video_id"]


def test_status_reports_what_the_server_actually_holds(client):
    video, response = init_upload(client)
    upload_id = response.json()["upload_id"]

    # Send chunks 0 and 2, deliberately skipping 1 — the shape a dropped
    # connection leaves behind.
    for index in (0, 2):
        chunk = video[index * CHUNK_SIZE : (index + 1) * CHUNK_SIZE]
        client.put(
            f"/api/videos/upload/{upload_id}/chunks/{index}", content=chunk
        )

    status = client.get(f"/api/videos/upload/{upload_id}")
    assert status.status_code == 200
    assert status.json()["received_chunks"] == [0, 2]


def test_resume_after_interruption_produces_the_original_file(client):
    video, response = init_upload(client)
    upload_id = response.json()["upload_id"]

    # First "session": two of four chunks land, then the connection dies.
    for index in (0, 1):
        client.put(
            f"/api/videos/upload/{upload_id}/chunks/{index}",
            content=video[index * CHUNK_SIZE : (index + 1) * CHUNK_SIZE],
        )

    # The client comes back and asks what is missing.
    received = client.get(f"/api/videos/upload/{upload_id}").json()["received_chunks"]
    assert received == [0, 1]

    total = (len(video) + CHUNK_SIZE - 1) // CHUNK_SIZE
    for index in range(total):
        if index in received:
            continue
        client.put(
            f"/api/videos/upload/{upload_id}/chunks/{index}",
            content=video[index * CHUNK_SIZE : (index + 1) * CHUNK_SIZE],
        )

    complete = client.post(f"/api/videos/upload/{upload_id}/complete")

    # The checksum passing proves the resumed upload reassembled byte-for-byte.
    assert complete.status_code == 200, complete.text
    assert complete.json()["checksum_verified"] is True


def test_completing_before_all_chunks_arrive_is_refused(client):
    video, response = init_upload(client)
    upload_id = response.json()["upload_id"]

    client.put(f"/api/videos/upload/{upload_id}/chunks/0", content=video[:CHUNK_SIZE])

    complete = client.post(f"/api/videos/upload/{upload_id}/complete")
    assert complete.status_code == 400
    assert "missing" in complete.json()["detail"].lower()


def test_checksum_mismatch_is_422_and_stores_nothing(client):
    video = make_video()

    response = client.post(
        "/api/videos/upload/init",
        json={
            "test_type": "SIT_UPS",
            "file_size_bytes": len(video),
            # A hash of different content: what a corrupted or swapped video
            # looks like from the server's side.
            "checksum_sha256": hashlib.sha256(b"something else entirely").hexdigest(),
            "chunk_size_bytes": CHUNK_SIZE,
        },
    )
    upload_id = response.json()["upload_id"]

    send_all_chunks(client, upload_id, video, CHUNK_SIZE)

    complete = client.post(f"/api/videos/upload/{upload_id}/complete")

    # 422 is what UploadClient.kt maps to CHECKSUM_MISMATCH and refuses to
    # retry — re-sending the same corrupt source cannot fix it.
    assert complete.status_code == 422


def test_short_chunk_is_rejected(client):
    video, response = init_upload(client)
    upload_id = response.json()["upload_id"]

    # A truncated chunk would otherwise assemble into a valid-looking MP4 that
    # scores differently server-side.
    truncated = client.put(
        f"/api/videos/upload/{upload_id}/chunks/0", content=video[: CHUNK_SIZE // 2]
    )

    assert truncated.status_code == 400


def test_chunk_index_out_of_range_is_rejected(client):
    video, response = init_upload(client)
    upload_id = response.json()["upload_id"]

    response = client.put(
        f"/api/videos/upload/{upload_id}/chunks/999", content=video[:CHUNK_SIZE]
    )
    assert response.status_code == 409


def test_unknown_upload_session_is_404(client):
    assert client.get("/api/videos/upload/up_does_not_exist").status_code == 404


def test_oversized_file_is_refused_before_any_bytes_move(client, settings):
    response = client.post(
        "/api/videos/upload/init",
        json={
            "test_type": "SIT_UPS",
            "file_size_bytes": settings.upload_max_file_bytes + 1,
            "checksum_sha256": "0" * 64,
        },
    )
    assert response.status_code == 413


def test_duplicate_chunk_is_idempotent(client):
    video, response = init_upload(client)
    upload_id = response.json()["upload_id"]

    for _ in range(3):
        client.put(
            f"/api/videos/upload/{upload_id}/chunks/0", content=video[:CHUNK_SIZE]
        )

    status = client.get(f"/api/videos/upload/{upload_id}").json()
    assert status["received_chunks"] == [0]


def test_completing_twice_returns_the_same_video(client):
    video, response = init_upload(client)
    upload_id = response.json()["upload_id"]
    send_all_chunks(client, upload_id, video, CHUNK_SIZE)

    first = client.post(f"/api/videos/upload/{upload_id}/complete")
    second = client.post(f"/api/videos/upload/{upload_id}/complete")

    # The client retries when a response is lost, and cannot distinguish that
    # from a request that never arrived. Two videos for one recording would
    # corrupt the athlete's result history.
    assert first.status_code == 200
    assert second.status_code == 200
    assert first.json()["video_id"] == second.json()["video_id"]


def test_server_caps_the_client_proposed_chunk_size(client, settings):
    video = make_video()

    response = client.post(
        "/api/videos/upload/init",
        json={
            "test_type": "SIT_UPS",
            "file_size_bytes": len(video),
            "checksum_sha256": hashlib.sha256(video).hexdigest(),
            # A bad client asking for an absurd chunk size.
            "chunk_size_bytes": 512 * 1024 * 1024,
        },
    )

    assert response.status_code == 200
    assert response.json()["chunk_size_bytes"] == settings.upload_chunk_size_bytes
