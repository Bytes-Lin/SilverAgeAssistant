from datetime import datetime
from decimal import Decimal

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models import (
    AuditLog,
    Binding,
    DeviceCredential,
    ElderModelConfiguration,
    ElderProfile,
    ModelConfigurationRequest,
)


class ModelConfigurationRepository:
    def __init__(self, session: AsyncSession) -> None:
        self.session = session

    async def get_elder(self, elder_id: str) -> ElderProfile | None:
        return await self.session.get(ElderProfile, elder_id)

    async def get_latest_binding_for_family(self, family_id: str, elder_id: str) -> Binding | None:
        query = (
            select(Binding)
            .where(
                Binding.family_account_id == family_id,
                Binding.elder_id == elder_id,
            )
            .order_by(Binding.created_at.desc())
            .limit(1)
        )
        return (await self.session.scalars(query)).one_or_none()

    async def get_active_binding_for_device(self, device: DeviceCredential) -> Binding | None:
        query = select(Binding).where(
            Binding.id == device.binding_id,
            Binding.elder_id == device.elder_id,
            Binding.revoked_at.is_(None),
        )
        return (await self.session.scalars(query)).one_or_none()

    async def list_active_device_ids(self, elder_id: str) -> set[str]:
        query = (
            select(DeviceCredential.id)
            .join(Binding, Binding.id == DeviceCredential.binding_id)
            .where(
                DeviceCredential.elder_id == elder_id,
                DeviceCredential.revoked_at.is_(None),
                Binding.elder_id == elder_id,
                Binding.revoked_at.is_(None),
            )
        )
        return set((await self.session.scalars(query)).all())

    async def get_configuration(self, elder_id: str) -> ElderModelConfiguration | None:
        query = select(ElderModelConfiguration).where(ElderModelConfiguration.elder_id == elder_id)
        return (await self.session.scalars(query)).one_or_none()

    async def get_request(
        self, family_id: str, client_request_id: str
    ) -> ModelConfigurationRequest | None:
        query = select(ModelConfigurationRequest).where(
            ModelConfigurationRequest.family_account_id == family_id,
            ModelConfigurationRequest.client_request_id == client_request_id,
        )
        return (await self.session.scalars(query)).one_or_none()

    async def create_configuration(
        self,
        *,
        elder_id: str,
        schema_version: int,
        revision: int,
        base_url: str,
        model: str,
        dialect: str,
        context_window_tokens: int,
        max_output_tokens: int,
        temperature: float,
        top_p: float,
        top_k: int,
        reasoning_enabled: bool,
        voice_websocket_url: str | None,
        voice_asr_model: str | None,
        voice_tts_model: str | None,
        voice_tts_voice: str | None,
        voice_tts_response_format: str | None,
        voice_tts_sample_rate: int | None,
        voice_tts_volume: int | None,
        voice_tts_rate: float | None,
        voice_tts_pitch: float | None,
        voice_language: str | None,
        family_id: str,
        client_request_id: str,
        now: datetime,
    ) -> ElderModelConfiguration:
        configuration = ElderModelConfiguration(
            elder_id=elder_id,
            schema_version=schema_version,
            revision=revision,
            base_url=base_url,
            model=model,
            dialect=dialect,
            context_window_tokens=context_window_tokens,
            max_output_tokens=max_output_tokens,
            temperature=Decimal(str(temperature)),
            top_p=Decimal(str(top_p)),
            top_k=top_k,
            reasoning_enabled=reasoning_enabled,
            voice_websocket_url=voice_websocket_url,
            voice_asr_model=voice_asr_model,
            voice_tts_model=voice_tts_model,
            voice_tts_voice=voice_tts_voice,
            voice_tts_response_format=voice_tts_response_format,
            voice_tts_sample_rate=voice_tts_sample_rate,
            voice_tts_volume=voice_tts_volume,
            voice_tts_rate=Decimal(str(voice_tts_rate)) if voice_tts_rate is not None else None,
            voice_tts_pitch=(
                Decimal(str(voice_tts_pitch)) if voice_tts_pitch is not None else None
            ),
            voice_language=voice_language,
            updated_by_family_id=family_id,
            created_at=now,
            updated_at=now,
            last_client_request_id=client_request_id,
        )
        self.session.add(configuration)
        await self.session.flush()
        return configuration

    async def update_configuration(
        self,
        configuration: ElderModelConfiguration,
        *,
        schema_version: int,
        revision: int,
        base_url: str,
        model: str,
        dialect: str,
        context_window_tokens: int,
        max_output_tokens: int,
        temperature: float,
        top_p: float,
        top_k: int,
        reasoning_enabled: bool,
        voice_websocket_url: str | None,
        voice_asr_model: str | None,
        voice_tts_model: str | None,
        voice_tts_voice: str | None,
        voice_tts_response_format: str | None,
        voice_tts_sample_rate: int | None,
        voice_tts_volume: int | None,
        voice_tts_rate: float | None,
        voice_tts_pitch: float | None,
        voice_language: str | None,
        family_id: str,
        client_request_id: str,
        now: datetime,
    ) -> ElderModelConfiguration:
        configuration.schema_version = schema_version
        configuration.revision = revision
        configuration.base_url = base_url
        configuration.model = model
        configuration.dialect = dialect
        configuration.context_window_tokens = context_window_tokens
        configuration.max_output_tokens = max_output_tokens
        configuration.temperature = Decimal(str(temperature))
        configuration.top_p = Decimal(str(top_p))
        configuration.top_k = top_k
        configuration.reasoning_enabled = reasoning_enabled
        configuration.voice_websocket_url = voice_websocket_url
        configuration.voice_asr_model = voice_asr_model
        configuration.voice_tts_model = voice_tts_model
        configuration.voice_tts_voice = voice_tts_voice
        configuration.voice_tts_response_format = voice_tts_response_format
        configuration.voice_tts_sample_rate = voice_tts_sample_rate
        configuration.voice_tts_volume = voice_tts_volume
        configuration.voice_tts_rate = (
            Decimal(str(voice_tts_rate)) if voice_tts_rate is not None else None
        )
        configuration.voice_tts_pitch = (
            Decimal(str(voice_tts_pitch)) if voice_tts_pitch is not None else None
        )
        configuration.voice_language = voice_language
        configuration.updated_by_family_id = family_id
        configuration.updated_at = now
        configuration.last_client_request_id = client_request_id
        await self.session.flush()
        return configuration

    def add_request(
        self,
        *,
        family_id: str,
        elder_id: str,
        client_request_id: str,
        request_fingerprint: str,
        response_payload: dict[str, object],
        revision: int,
        created_at: datetime,
    ) -> None:
        self.session.add(
            ModelConfigurationRequest(
                family_account_id=family_id,
                elder_id=elder_id,
                client_request_id=client_request_id,
                request_fingerprint=request_fingerprint,
                response_payload=response_payload,
                revision=revision,
                created_at=created_at,
            )
        )

    def add_update_audit(
        self,
        *,
        family_id: str,
        elder_id: str,
        configuration_id: str,
        revision: int,
        model: str,
        dialect: str,
    ) -> None:
        self.session.add(
            AuditLog(
                action="MODEL_CONFIG_UPDATED",
                actor_type="FAMILY",
                actor_id=family_id,
                resource_type="MODEL_CONFIGURATION",
                resource_id=configuration_id,
                details={
                    "elder_id": elder_id,
                    "revision": str(revision),
                    "model": model,
                    "dialect": dialect,
                },
            )
        )
