from datetime import datetime
from uuid import UUID

from pydantic import Field

from app.schemas.common import StrictSchema


class FamilyRegisterRequest(StrictSchema):
    display_name: str = Field(min_length=1, max_length=20)
    mobile_number: str = Field(
        min_length=1,
        max_length=30,
        description="中国大陆手机号；接受 11 位号码以及 +86/0086 前缀",
    )
    client_request_id: UUID


class FamilyRegisterResponse(StrictSchema):
    family_account_id: UUID
    display_name: str
    family_mobile_masked: str
    access_token: str
    refresh_token: str
    token_type: str = "bearer"
    access_token_expires_at: datetime


class RefreshRequest(StrictSchema):
    refresh_token: str = Field(min_length=1, max_length=2048)


class AccessTokenResponse(StrictSchema):
    access_token: str
    token_type: str = "bearer"
    access_token_expires_at: datetime
