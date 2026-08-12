## 1. athletes
| Column | Type | Constraints | Description |
|---|---|---|---|
| id | UUID | PRIMARY KEY | |
| name | VARCHAR(150) | NOT NULL | |
| dob | DATE | NOT NULL | |
| gender | VARCHAR(20) | NOT NULL, CHECK IN ('male','female','other') | |
| region | VARCHAR(100) | NOT NULL | |
| height_cm | DECIMAL(5,2) | NULL | Self-reported |
| weight_kg | DECIMAL(5,2) | NULL | Self-reported |
| phone | VARCHAR(20) | UNIQUE, NOT NULL | |
| reference_face_key | VARCHAR(500) | NULL | S3 key, captured once at registration |
| created_at | TIMESTAMP | NOT NULL | |
| updated_at | TIMESTAMP | NOT NULL | |

## 2. tests

| Column | Type | Constraints | Description |
|---|---|---|---|
| id | UUID | PRIMARY KEY | Unique test ID |
| name | VARCHAR(100) | UNIQUE, NOT NULL | Test name |
| description | TEXT | NULL | Test instructions/description |
| unit | VARCHAR(30) | NOT NULL | Result unit, e.g. cm or reps |
| created_at | TIMESTAMP | NOT NULL | Test creation time |

## 3. test_results
| Column | Type | Constraints | Description |
|---|---|---|---|
| id | UUID | PRIMARY KEY | |
| athlete_id | UUID | NOT NULL, FK → athletes.id | |
| test_id | UUID | NOT NULL, FK → tests.id | |
| attempt_number | INT | NOT NULL, DEFAULT 1 | Which attempt this is for this athlete+test |
| provisional_score | DECIMAL(10,2) | NULL | On-device |
| server_score | DECIMAL(10,2) | NULL | Server re-verified |
| final_score | DECIMAL(10,2) | NULL | Set only after official approval |
| status | VARCHAR(30) | NOT NULL, CHECK IN ('pending_sync','processing','verified','flagged','approved','rejected') | |
| created_at | TIMESTAMP | NOT NULL | |
| updated_at | TIMESTAMP | NOT NULL | |

## 4. videos
| Column | Type | Constraints | Description |
|---|---|---|---|
| id | UUID | PRIMARY KEY | |
| test_result_id | UUID | NOT NULL, FK → test_results.id | |
| s3_key | VARCHAR(500) | NOT NULL | |
| checksum_sha256 | VARCHAR(64) | NOT NULL | Tamper-detection integrity hash |
| file_size_bytes | BIGINT | NULL | |
| duration_seconds | DECIMAL(10,2) | NULL | |
| sensor_telemetry_key | VARCHAR(500) | NULL | S3 key for accelerometer/gyroscope log (cheat-check cross-reference) |
| created_at | TIMESTAMP | NOT NULL | |

## 5. flags
| Column | Type | Constraints | Description |
|---|---|---|---|
| id | UUID | PRIMARY KEY | |
| test_result_id | UUID | NOT NULL, FK → test_results.id | |
| source | VARCHAR(20) | NOT NULL, CHECK IN ('auto','manual') | Who/what raised it |
| reason | VARCHAR(255) | NOT NULL | |
| severity | VARCHAR(20) | NOT NULL, CHECK IN ('low','medium','high') | |
| resolved_by | UUID | NULL, FK → officials.id | |
| resolution | VARCHAR(30) | NULL, CHECK IN ('confirmed','dismissed') | |
| created_at | TIMESTAMP | NOT NULL | |
| resolved_at | TIMESTAMP | NULL | |

## 6. face_verifications
| Column | Type | Constraints | Description |
|---|---|---|---|
| id | UUID | PRIMARY KEY | |
| test_result_id | UUID | NOT NULL, FK → test_results.id | Per-submission, not per-athlete |
| verification_status | VARCHAR(20) | NOT NULL, CHECK IN ('pass','fail','manual_review') | |
| similarity_score | DECIMAL(5,4) | NULL | |
| verified_at | TIMESTAMP | NULL | |
| created_at | TIMESTAMP | NOT NULL | |

## 7. officials  [NEW]
| Column | Type | Constraints | Description |
|---|---|---|---|
| id | UUID | PRIMARY KEY | |
| name | VARCHAR(150) | NOT NULL | |
| email | VARCHAR(150) | UNIQUE, NOT NULL | |
| role | VARCHAR(30) | NOT NULL, CHECK IN ('sai_admin','regional_reviewer') | |
| region | VARCHAR(100) | NULL | Null for sai_admin (sees all regions) |
| created_at | TIMESTAMP | NOT NULL | |

## 8. review_actions  [NEW — the audit trail]
| Column | Type | Constraints | Description |
|---|---|---|---|
| id | UUID | PRIMARY KEY | |
| test_result_id | UUID | NOT NULL, FK → test_results.id | |
| official_id | UUID | NOT NULL, FK → officials.id | |
| action | VARCHAR(30) | NOT NULL, CHECK IN ('approved','rejected','requested_resubmission') | |
| notes | TEXT | NULL | |
| created_at | TIMESTAMP | NOT NULL | |

## 9. benchmarks  [NEW]
| Column | Type | Constraints | Description |
|---|---|---|---|
| id | UUID | PRIMARY KEY | |
| test_id | UUID | NOT NULL, FK → tests.id | |
| gender | VARCHAR(20) | NOT NULL | |
| age_min | INT | NOT NULL | |
| age_max | INT | NOT NULL | |
| percentile_50 | DECIMAL(10,2) | NOT NULL | |
| percentile_75 | DECIMAL(10,2) | NOT NULL | |
| percentile_90 | DECIMAL(10,2) | NOT NULL | |
| created_at | TIMESTAMP | NOT NULL | |