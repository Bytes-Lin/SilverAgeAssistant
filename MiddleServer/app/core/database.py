import asyncio
from collections.abc import AsyncIterator

from sqlalchemy import event
from sqlalchemy.ext.asyncio import (
    AsyncEngine,
    AsyncSession,
    async_sessionmaker,
    create_async_engine,
)

from app.core.config import Settings
from app.models.base import Base


class Database:
    def __init__(self, settings: Settings) -> None:
        self.engine: AsyncEngine = create_async_engine(
            settings.database_url,
            connect_args={"timeout": 30} if settings.database_url.startswith("sqlite") else {},
        )
        self.session_factory = async_sessionmaker(self.engine, expire_on_commit=False)
        self.binding_lock = asyncio.Lock()
        self.command_lock = asyncio.Lock()
        self.model_configuration_lock = asyncio.Lock()
        self.model_usage_lock = asyncio.Lock()
        self.safety_monitoring_lock = asyncio.Lock()

        if settings.database_url.startswith("sqlite"):
            event.listen(self.engine.sync_engine, "connect", self._configure_sqlite)

    @staticmethod
    def _configure_sqlite(dbapi_connection: object, _connection_record: object) -> None:
        cursor = dbapi_connection.cursor()  # type: ignore[attr-defined]
        cursor.execute("PRAGMA foreign_keys=ON")
        cursor.execute("PRAGMA journal_mode=WAL")
        cursor.close()

    async def create_schema(self) -> None:
        async with self.engine.begin() as connection:
            await connection.run_sync(Base.metadata.create_all)

    async def dispose(self) -> None:
        await self.engine.dispose()

    async def session(self) -> AsyncIterator[AsyncSession]:
        async with self.session_factory() as session:
            yield session
