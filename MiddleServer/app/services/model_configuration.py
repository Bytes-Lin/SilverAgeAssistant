import asyncio
import hashlib
import json
from typing import Protocol
from uuid import UUID

from sqlalchemy.ext.asyncio import AsyncSession

from app.core.errors import ApiError
from app.core.security import ensure_utc, utc_now
from app.models import (
    Binding,
    DeviceCredential,
    ElderModelConfiguration,
    FamilyAccount,
)
from app.repositories.model_configuration import ModelConfigurationRepository
from app.schemas.model_configuration import (
    DEFAULT_CONTEXT_WINDOW_TOKENS,
    ModelConfiguration,
    ModelConfigurationResponse,
    ModelConfigurationUpdateRequest,
    ModelDialect,
    ModelSamplingConfiguration,
    VoiceModelConfiguration,
)


class ModelConfigurationNotifier(Protocol):
    async def notify_model_config_available(
        self,
        elder_id: str,
        active_device_ids: set[str],
        revision: int,
    ) -> bool: ...


class ModelConfigurationService:
    def __init__(
        self,
        session: AsyncSession,
        model_configuration_lock: asyncio.Lock,
        notifier: ModelConfigurationNotifier,
    ) -> None:
        self.session = session
        self.model_configuration_lock = model_configuration_lock
        self.notifier = notifier
        self.repository = ModelConfigurationRepository(session)

    async def get_for_family(
        self, family: FamilyAccount, elder_id: str
    ) -> ModelConfigurationResponse:
        async with self.session.begin():
            await self._require_family_binding(family.id, elder_id)
            configuration = await self.repository.get_configuration(elder_id)
            if configuration is None:
                raise ApiError(404, "MODEL_CONFIG_NOT_FOUND", "尚未设置模型配置")
            return self._response(configuration)

    async def get_for_device(self, device: DeviceCredential) -> ModelConfigurationResponse:
        async with self.session.begin():
            binding = await self.repository.get_active_binding_for_device(device)
            elder = await self.repository.get_elder(device.elder_id)
            if binding is None or elder is None or not elder.is_active:
                raise ApiError(410, "BINDING_REVOKED", "绑定关系已撤销")
            configuration = await self.repository.get_configuration(device.elder_id)
            if configuration is None:
                raise ApiError(404, "MODEL_CONFIG_NOT_FOUND", "尚未设置模型配置")
            return self._response(configuration)

    async def update_for_family(
        self,
        family: FamilyAccount,
        elder_id: str,
        request: ModelConfigurationUpdateRequest,
        idempotency_key: str | None,
    ) -> ModelConfigurationResponse:
        self._validate_idempotency_key(idempotency_key, request.client_request_id)
        request_id = str(request.client_request_id)
        fingerprint = self._fingerprint(elder_id, request)

        async with self.model_configuration_lock:
            async with self.session.begin():
                await self._require_family_binding(family.id, elder_id)
                previous_request = await self.repository.get_request(family.id, request_id)
                if previous_request is not None:
                    if previous_request.request_fingerprint != fingerprint:
                        raise ApiError(
                            409,
                            "IDEMPOTENCY_CONFLICT",
                            "同一请求标识对应了不同内容",
                        )
                    response_payload = dict(previous_request.response_payload)
                    raw_configuration = response_payload.get("configuration")
                    stored_configuration = (
                        dict(raw_configuration) if isinstance(raw_configuration, dict) else {}
                    )
                    stored_configuration.setdefault(
                        "context_window_tokens",
                        DEFAULT_CONTEXT_WINDOW_TOKENS,
                    )
                    response_payload["configuration"] = stored_configuration
                    return ModelConfigurationResponse.model_validate(response_payload)

                current = await self.repository.get_configuration(elder_id)
                current_revision = current.revision if current is not None else None
                if request.expected_revision != current_revision:
                    raise ApiError(
                        409,
                        "MODEL_CONFIG_REVISION_CONFLICT",
                        "模型配置已被其他操作更新，请刷新后重试",
                    )

                now = utc_now()
                revision = (current_revision or 0) + 1
                voice = request.voice
                if current is None:
                    saved = await self.repository.create_configuration(
                        elder_id=elder_id,
                        schema_version=request.schema_version,
                        revision=revision,
                        base_url=request.base_url,
                        model=request.model,
                        dialect=request.dialect.value,
                        context_window_tokens=request.context_window_tokens,
                        max_output_tokens=request.max_output_tokens,
                        temperature=request.sampling.temperature,
                        top_p=request.sampling.top_p,
                        top_k=request.sampling.top_k,
                        reasoning_enabled=request.reasoning_enabled,
                        voice_websocket_url=voice.websocket_url if voice else None,
                        voice_asr_model=voice.asr_model if voice else None,
                        voice_tts_model=voice.tts_model if voice else None,
                        voice_tts_voice=voice.tts_voice if voice else None,
                        voice_tts_response_format=(
                            voice.tts_response_format.value if voice else None
                        ),
                        voice_tts_sample_rate=voice.tts_sample_rate if voice else None,
                        voice_tts_volume=voice.tts_volume if voice else None,
                        voice_tts_rate=voice.tts_rate if voice else None,
                        voice_tts_pitch=voice.tts_pitch if voice else None,
                        voice_language=voice.language if voice else None,
                        family_id=family.id,
                        client_request_id=request_id,
                        now=now,
                    )
                else:
                    saved = await self.repository.update_configuration(
                        current,
                        schema_version=request.schema_version,
                        revision=revision,
                        base_url=request.base_url,
                        model=request.model,
                        dialect=request.dialect.value,
                        context_window_tokens=request.context_window_tokens,
                        max_output_tokens=request.max_output_tokens,
                        temperature=request.sampling.temperature,
                        top_p=request.sampling.top_p,
                        top_k=request.sampling.top_k,
                        reasoning_enabled=request.reasoning_enabled,
                        voice_websocket_url=voice.websocket_url if voice else None,
                        voice_asr_model=voice.asr_model if voice else None,
                        voice_tts_model=voice.tts_model if voice else None,
                        voice_tts_voice=voice.tts_voice if voice else None,
                        voice_tts_response_format=(
                            voice.tts_response_format.value if voice else None
                        ),
                        voice_tts_sample_rate=voice.tts_sample_rate if voice else None,
                        voice_tts_volume=voice.tts_volume if voice else None,
                        voice_tts_rate=voice.tts_rate if voice else None,
                        voice_tts_pitch=voice.tts_pitch if voice else None,
                        voice_language=voice.language if voice else None,
                        family_id=family.id,
                        client_request_id=request_id,
                        now=now,
                    )
                response = self._response(saved)
                self.repository.add_request(
                    family_id=family.id,
                    elder_id=elder_id,
                    client_request_id=request_id,
                    request_fingerprint=fingerprint,
                    response_payload=response.model_dump(mode="json"),
                    revision=revision,
                    created_at=now,
                )
                self.repository.add_update_audit(
                    family_id=family.id,
                    elder_id=elder_id,
                    configuration_id=saved.id,
                    revision=revision,
                    model=saved.model,
                    dialect=saved.dialect,
                )
                active_device_ids = await self.repository.list_active_device_ids(elder_id)

        try:
            await self.notifier.notify_model_config_available(
                elder_id,
                active_device_ids,
                response.revision,
            )
        except Exception:
            # The REST read path remains authoritative when a best-effort hint fails.
            pass
        return response

    async def _require_family_binding(self, family_id: str, elder_id: str) -> Binding:
        elder = await self.repository.get_elder(elder_id)
        binding = await self.repository.get_latest_binding_for_family(family_id, elder_id)
        if elder is None or not elder.is_active or binding is None:
            raise ApiError(403, "MODEL_CONFIG_FORBIDDEN", "没有模型配置访问权限")
        if binding.revoked_at is not None:
            raise ApiError(410, "BINDING_REVOKED", "绑定关系已撤销")
        return binding

    @staticmethod
    def _validate_idempotency_key(value: str | None, request_id: UUID) -> None:
        try:
            header_id = UUID(value) if value else None
        except ValueError as exc:
            raise ApiError(400, "REQUEST_VALIDATION_ERROR", "幂等请求标识不正确") from exc
        if header_id != request_id:
            raise ApiError(400, "REQUEST_VALIDATION_ERROR", "幂等请求标识不一致")

    @staticmethod
    def _fingerprint(
        elder_id: str,
        request: ModelConfigurationUpdateRequest,
    ) -> str:
        data = {
            "elder_id": elder_id,
            **request.model_dump(
                mode="json",
                exclude={"client_request_id"},
            ),
        }
        encoded = json.dumps(
            data,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        )
        return hashlib.sha256(encoded.encode()).hexdigest()

    @staticmethod
    def _response(
        configuration: ElderModelConfiguration,
    ) -> ModelConfigurationResponse:
        voice = None
        if configuration.voice_websocket_url is not None:
            voice = VoiceModelConfiguration.model_validate(
                {
                    "websocket_url": configuration.voice_websocket_url,
                    "asr_model": configuration.voice_asr_model,
                    "tts_model": configuration.voice_tts_model,
                    "tts_voice": configuration.voice_tts_voice,
                    "tts_response_format": configuration.voice_tts_response_format,
                    "tts_sample_rate": configuration.voice_tts_sample_rate,
                    "tts_volume": configuration.voice_tts_volume,
                    "tts_rate": (
                        float(configuration.voice_tts_rate)
                        if configuration.voice_tts_rate is not None
                        else None
                    ),
                    "tts_pitch": (
                        float(configuration.voice_tts_pitch)
                        if configuration.voice_tts_pitch is not None
                        else None
                    ),
                    "language": configuration.voice_language,
                }
            )
        return ModelConfigurationResponse(
            configuration=ModelConfiguration(
                schema_version=1,
                base_url=configuration.base_url,
                model=configuration.model,
                dialect=ModelDialect(configuration.dialect),
                context_window_tokens=configuration.context_window_tokens,
                max_output_tokens=configuration.max_output_tokens,
                sampling=ModelSamplingConfiguration(
                    temperature=float(configuration.temperature),
                    top_p=float(configuration.top_p),
                    top_k=configuration.top_k,
                ),
                reasoning_enabled=False,
                voice=voice,
            ),
            revision=configuration.revision,
            updated_at=ensure_utc(configuration.updated_at),
        )
