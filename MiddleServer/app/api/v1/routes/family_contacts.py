from fastapi import APIRouter, Depends, Response
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.dependencies import get_current_device, get_session
from app.models import DeviceCredential
from app.schemas.common import ErrorResponse
from app.schemas.family_contacts import FamilyContactsResponse
from app.services.family_contacts import FamilyContactsService

router = APIRouter(tags=["device-family-contacts"])


@router.get(
    "/devices/me/family-contacts",
    response_model=FamilyContactsResponse,
    responses={
        401: {"model": ErrorResponse, "description": "Device credential is invalid"},
        403: {"model": ErrorResponse, "description": "Contact access is forbidden"},
        503: {"model": ErrorResponse, "description": "Snapshot is temporarily unavailable"},
    },
    summary="Get the elder device's complete family contact snapshot",
)
async def get_family_contacts(
    response: Response,
    device: DeviceCredential = Depends(get_current_device),
    session: AsyncSession = Depends(get_session),
) -> FamilyContactsResponse:
    snapshot = await FamilyContactsService(session).get_snapshot(device)
    response.headers["Cache-Control"] = "no-store"
    return snapshot
