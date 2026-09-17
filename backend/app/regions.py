"""The regions an athlete can register in.

A fixed list, not free text, because `region` is what scopes a regional
reviewer's queue. An athlete who types "Tamilnadu" where the reviewer's account
says "Tamil Nadu" is invisible to the one official responsible for them — no
error, no flag, just a test nobody ever reviews.

The mobile app carries the same list (`Regions.kt`); `test_regions.py` keeps
them identical.
"""

from __future__ import annotations

STATES_AND_UNION_TERRITORIES: tuple[str, ...] = (
    # States
    "Andhra Pradesh",
    "Arunachal Pradesh",
    "Assam",
    "Bihar",
    "Chhattisgarh",
    "Goa",
    "Gujarat",
    "Haryana",
    "Himachal Pradesh",
    "Jharkhand",
    "Karnataka",
    "Kerala",
    "Madhya Pradesh",
    "Maharashtra",
    "Manipur",
    "Meghalaya",
    "Mizoram",
    "Nagaland",
    "Odisha",
    "Punjab",
    "Rajasthan",
    "Sikkim",
    "Tamil Nadu",
    "Telangana",
    "Tripura",
    "Uttar Pradesh",
    "Uttarakhand",
    "West Bengal",
    # Union territories
    "Andaman and Nicobar Islands",
    "Chandigarh",
    "Dadra and Nagar Haveli and Daman and Diu",
    "Delhi",
    "Jammu and Kashmir",
    "Ladakh",
    "Lakshadweep",
    "Puducherry",
)

_BY_FOLDED = {name.casefold(): name for name in STATES_AND_UNION_TERRITORIES}


def canonical_region(value: str) -> str | None:
    """The canonical spelling, tolerant of case and surrounding whitespace only.

    Deliberately no fuzzy matching: guessing that "Punjab" meant something else
    would route an athlete to the wrong reviewer, which is worse than asking.
    """
    return _BY_FOLDED.get(" ".join(value.split()).casefold())
