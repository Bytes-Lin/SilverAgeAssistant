from datetime import datetime
from enum import StrEnum
from uuid import UUID

from pydantic import Field

from app.schemas.common import StrictSchema


class Relationship(StrEnum):
    CHILD = "CHILD"
    RELATIVE = "RELATIVE"
    CAREGIVER = "CAREGIVER"
    OTHER = "OTHER"


class ElderCreateRequest(StrictSchema):
    display_name: str = Field(min_length=1, max_length=20)
    mobile_number: str = Field(
        min_length=1,
        max_length=30,
        description="中国大陆手机号；接受 11 位号码以及 +86/0086 前缀",
    )
    relationship: Relationship
    emergency_contact: bool
    client_request_id: UUID


class ElderResponse(StrictSchema):
    elder_id: UUID
    display_name: str
    elder_mobile_masked: str
    relationship: Relationship
    emergency_contact: bool
    created_at: datetime


class BindingCodeCreateRequest(StrictSchema):
    elder_id: UUID
    client_request_id: UUID


class BindingCodeResponse(StrictSchema):
    binding_code: str = Field(pattern=r"^\d{6}$")
    expires_at: datetime
    elder_id: UUID
    family_mobile_masked: str


class DeviceBindRequest(StrictSchema):
    binding_code: str = Field(pattern=r"^\d{6}$")
    family_mobile_number: str = Field(
        min_length=1,
        max_length=30,
        description="生成绑定码的家属中国大陆手机号",
    )
    elder_display_name: str = Field(min_length=1, max_length=20)
    sharing_consent: bool
    device_id: str = Field(min_length=1, max_length=128, pattern=r"^[A-Za-z0-9._:-]+$")
    device_name: str | None = Field(default=None, max_length=80)
    client_request_id: UUID


class DeviceBindResponse(StrictSchema):
    binding_id: UUID
    elder_id: UUID
    family_account_id: UUID
    family_mobile_masked: str
    relationship: Relationship
    permissions: list[str]
    device_credential: str
    bound_at: datetime = Field(description="本次设备凭据生效时间")


class BindingResponse(StrictSchema):
    binding_id: UUID
    elder_id: UUID
    elder_display_name: str
    family_account_id: UUID
    family_display_name: str
    family_mobile_masked: str
    relationship: Relationship
    permissions: list[str]
    device_id: str | None
    device_name: str | None
    bound_at: datetime
    revoked_at: datetime | None


class BindingListResponse(StrictSchema):
    bindings: list[BindingResponse]
