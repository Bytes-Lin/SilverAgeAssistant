from fastapi import APIRouter

from app.api.v1.routes import auth, bindings, commands, family_contacts, realtime

api_router = APIRouter(prefix="/api/v1")
api_router.include_router(auth.router)
api_router.include_router(bindings.router)
api_router.include_router(commands.router)
api_router.include_router(family_contacts.router)
api_router.include_router(realtime.router)
