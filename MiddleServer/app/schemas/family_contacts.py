from datetime import datetime
from uuid import UUID

from pydantic import Field

from app.schemas.binding import Relationship
from app.schemas.common import StrictSchema


class FamilyContact(StrictSchema):
    binding_id: UUID
    family_account_id: UUID
    display_name: str
    mobile_number: str = Field(pattern=r"^1[3-9]\d{9}$")
    relationship: Relationship
    permissions: list[str]
    emergency_contact: bool
    bound_at: datetime
    profile_updated_at: datetime


class FamilyContactsResponse(StrictSchema):
    snapshot_version: str = Field(
        description="Stable opaque version of all currently visible contact fields"
    )
    synced_at: datetime
    contacts: list[FamilyContact] = Field(
        description="Complete snapshot; an empty list removes previously cached contacts"
    )
