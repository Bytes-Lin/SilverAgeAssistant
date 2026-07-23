import asyncio
import hashlib
import json
from dataclasses import dataclass
from datetime import UTC, date, datetime, time, timedelta
from typing import Protocol
from uuid import UUID
from zoneinfo import ZoneInfo

from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import Settings
from app.core.errors import ApiError
from app.core.security import ensure_utc, utc_now
from app.models import (
    DeviceCredential,
    FamilyAccount,
    ModelUsageItem,
)
from app.models import (
    ModelUsageRefreshRequest as StoredModelUsageRefreshRequest,
)
from app.repositories.model_usage import ModelUsageRepository
from app.schemas.model_usage import (
    MAX_USAGE_COUNTER,
    DailyModelUsage,
    DailyModelUsageResponse,
    ModelUsageBatchRequest,
    ModelUsageBatchResponse,
    ModelUsageItemRequest,
    ModelUsageModality,
    ModelUsageRefreshResponse,
    ModelUsageSummaryResponse,
    ModelUsageTimeZoneSource,
    ModelUsageTotals,
)
from app.schemas.model_usage import (
    ModelUsageRefreshRequest as ModelUsageRefreshPayload,
)


class ModelUsageRefreshNotifier(Protocol):
    async def notify_model_usage_report_requested(
        self,
        elder_id: str,
        active_device_ids: set[str],
        client_request_id: str,
        requested_at: datetime,
    ) -> bool: ...


@dataclass
class AggregatedUsageItem:
    modality: ModelUsageModality
    provider: str
    model: str | None
    feature: str
    request_count: int = 0
    success_count: int = 0
    input_tokens: int = 0
    output_tokens: int = 0
    asr_audio_duration_ms: int = 0
    tts_character_count: int = 0
    tts_audio_duration_ms: int = 0
    contains_estimated_values: bool = False


class ModelUsageService:
    def __init__(
        self,
        session: AsyncSession,
        settings: Settings,
        model_usage_lock: asyncio.Lock,
        refresh_notifier: ModelUsageRefreshNotifier,
    ) -> None:
        self.session = session
        self.settings = settings
        self.model_usage_lock = model_usage_lock
        self.refresh_notifier = refresh_notifier
        self.repository = ModelUsageRepository(session)

    async def accept_batch(
        self,
        device: DeviceCredential,
        request: ModelUsageBatchRequest,
        idempotency_key: str | None,
    ) -> ModelUsageBatchResponse:
        self._validate_idempotency_key(idempotency_key, request.batch_id)
        started_at = self._require_utc(
            request.period_started_at,
            "INVALID_USAGE_BATCH",
        )
        ended_at = self._require_utc(
            request.period_ended_at,
            "INVALID_USAGE_BATCH",
        )
        if (
            started_at >= ended_at
            or ended_at - started_at > timedelta(days=7)
            or ended_at > utc_now() + timedelta(minutes=10)
        ):
            raise ApiError(400, "INVALID_USAGE_BATCH", "用量批次时间范围不正确")

        fingerprint = self._fingerprint(request)
        aggregated_items = self._aggregate_items(request.items)
        batch_id = str(request.batch_id)

        async with self.model_usage_lock:
            async with self.session.begin():
                binding = await self.repository.get_active_binding_for_device(device)
                elder = await self.repository.get_elder(device.elder_id)
                if binding is None or elder is None or not elder.is_active:
                    raise ApiError(401, "AUTHENTICATION_REQUIRED", "设备凭据无效或绑定已撤销")

                existing = await self.repository.get_batch(batch_id)
                if existing is not None:
                    if (
                        existing.elder_id != device.elder_id
                        or existing.request_fingerprint != fingerprint
                    ):
                        raise ApiError(
                            409,
                            "IDEMPOTENCY_CONFLICT",
                            "同一批次标识对应了不同的请求内容",
                        )
                    return ModelUsageBatchResponse(
                        batch_id=UUID(existing.batch_id),
                        accepted=True,
                        received_at=ensure_utc(existing.received_at),
                    )

                received_at = utc_now()
                await self.repository.create_batch(
                    batch_id=batch_id,
                    elder_id=device.elder_id,
                    device_id=device.id,
                    period_started_at=started_at,
                    period_ended_at=ended_at,
                    time_zone=request.time_zone,
                    time_zone_source=request.time_zone_source.value,
                    request_fingerprint=fingerprint,
                    received_at=received_at,
                )
                for item in aggregated_items:
                    self.repository.add_item(
                        batch_id=batch_id,
                        modality=item.modality.value,
                        provider=item.provider,
                        model=item.model,
                        feature=item.feature,
                        request_count=item.request_count,
                        success_count=item.success_count,
                        input_tokens=item.input_tokens,
                        output_tokens=item.output_tokens,
                        asr_audio_duration_ms=item.asr_audio_duration_ms,
                        tts_character_count=item.tts_character_count,
                        tts_audio_duration_ms=item.tts_audio_duration_ms,
                        contains_estimated_values=item.contains_estimated_values,
                    )
                self.repository.add_acceptance_audit(
                    device_id=device.id,
                    elder_id=device.elder_id,
                    batch_id=batch_id,
                    item_count=len(aggregated_items),
                )
                return ModelUsageBatchResponse(
                    batch_id=request.batch_id,
                    accepted=True,
                    received_at=received_at,
                )

    async def summarize_for_family(
        self,
        family: FamilyAccount,
        elder_id: str,
        period_started_at: datetime,
        period_ended_at: datetime,
    ) -> ModelUsageSummaryResponse:
        started_at = self._require_utc(period_started_at, "INVALID_USAGE_QUERY")
        ended_at = self._require_utc(period_ended_at, "INVALID_USAGE_QUERY")
        if started_at >= ended_at or ended_at - started_at > timedelta(days=366):
            raise ApiError(400, "INVALID_USAGE_QUERY", "用量查询时间范围不正确")

        async with self.session.begin():
            binding = await self.repository.get_active_family_binding(family.id, elder_id)
            elder = await self.repository.get_elder(elder_id)
            if binding is None or elder is None or not elder.is_active:
                raise ApiError(403, "BINDING_FORBIDDEN", "没有该老人档案的用量查看权限")
            rows = await self.repository.list_items_for_period(
                elder_id,
                started_at,
                ended_at,
            )

        totals = self._empty_totals()
        last_reported_at: datetime | None = None
        for item, received_at in rows:
            self._accumulate_totals(totals, item)
            normalized_received_at = ensure_utc(received_at)
            if last_reported_at is None or normalized_received_at > last_reported_at:
                last_reported_at = normalized_received_at

        return ModelUsageSummaryResponse(
            elder_id=UUID(elder_id),
            period_started_at=started_at,
            period_ended_at=ended_at,
            totals=totals,
            last_reported_at=last_reported_at,
        )

    async def daily_for_family(
        self,
        family: FamilyAccount,
        elder_id: str,
    ) -> DailyModelUsageResponse:
        async with self.session.begin():
            binding = await self.repository.get_active_family_binding(
                family.id,
                elder_id,
            )
            elder = await self.repository.get_elder(elder_id)
            if binding is None or elder is None or not elder.is_active:
                raise ApiError(
                    403,
                    "BINDING_FORBIDDEN",
                    "没有该老人档案的每日用量查看权限",
                )
            time_zone_batch = await self.repository.get_latest_time_zone_batch(
                elder_id,
                ModelUsageTimeZoneSource.LOCATION.value,
            )
            if time_zone_batch is None:
                time_zone_batch = await self.repository.get_latest_time_zone_batch(elder_id)

            if time_zone_batch is None:
                time_zone_name = "UTC"
                time_zone_source = ModelUsageTimeZoneSource.SYSTEM_FALLBACK
            else:
                time_zone_name = time_zone_batch.time_zone
                time_zone_source = ModelUsageTimeZoneSource(time_zone_batch.time_zone_source)
            time_zone = ZoneInfo(time_zone_name)
            current_date = utc_now().astimezone(time_zone).date()
            period_started_on = current_date.replace(day=1)
            period_ended_on = self._first_day_of_next_month(period_started_on)
            period_started_at = datetime.combine(
                period_started_on,
                time.min,
                tzinfo=time_zone,
            ).astimezone(UTC)
            period_ended_at = datetime.combine(
                period_ended_on,
                time.min,
                tzinfo=time_zone,
            ).astimezone(UTC)
            rows = await self.repository.list_items_with_batch_for_period(
                elder_id,
                period_started_at,
                period_ended_at,
            )

        totals_by_date: dict[date, ModelUsageTotals] = {}
        day = period_started_on
        while day < period_ended_on:
            totals_by_date[day] = self._empty_totals()
            day += timedelta(days=1)

        last_reported_at: datetime | None = None
        for item, batch_started_at, received_at in rows:
            local_date = ensure_utc(batch_started_at).astimezone(time_zone).date()
            totals = totals_by_date.get(local_date)
            if totals is not None:
                self._accumulate_totals(totals, item)
            normalized_received_at = ensure_utc(received_at)
            if last_reported_at is None or normalized_received_at > last_reported_at:
                last_reported_at = normalized_received_at

        return DailyModelUsageResponse(
            elder_id=UUID(elder_id),
            period_started_on=period_started_on,
            period_ended_on=period_ended_on,
            current_date=current_date,
            timezone=time_zone_name,
            timezone_source=time_zone_source,
            days=[
                DailyModelUsage(date=day_date, totals=totals)
                for day_date, totals in totals_by_date.items()
            ],
            last_reported_at=last_reported_at,
        )

    async def request_current_usage(
        self,
        family: FamilyAccount,
        elder_id: str,
        request: ModelUsageRefreshPayload,
        idempotency_key: str | None,
    ) -> ModelUsageRefreshResponse:
        self._validate_refresh_idempotency_key(
            idempotency_key,
            request.client_request_id,
        )
        client_request_id = str(request.client_request_id)
        fingerprint = hashlib.sha256(elder_id.encode()).hexdigest()

        async with self.model_usage_lock:
            async with self.session.begin():
                binding = await self.repository.get_active_family_binding(
                    family.id,
                    elder_id,
                )
                elder = await self.repository.get_elder(elder_id)
                if binding is None or elder is None or not elder.is_active:
                    raise ApiError(
                        403,
                        "BINDING_FORBIDDEN",
                        "没有该老人档案的用量刷新权限",
                    )

                existing = await self.repository.get_refresh_request(
                    family.id,
                    client_request_id,
                )
                if existing is not None:
                    if existing.request_fingerprint != fingerprint:
                        raise ApiError(
                            409,
                            "IDEMPOTENCY_CONFLICT",
                            "同一请求标识对应了不同的老人档案",
                        )
                    return self._refresh_response(existing)

                requested_at = utc_now()
                latest = await self.repository.get_latest_refresh_request(
                    family.id,
                    elder_id,
                )
                if latest is not None and requested_at - ensure_utc(
                    latest.requested_at
                ) < timedelta(seconds=self.settings.usage_refresh_min_interval_seconds):
                    raise ApiError(
                        429,
                        "USAGE_REFRESH_RATE_LIMITED",
                        "刷新过于频繁，请稍后再试",
                    )

                active_device_ids = await self.repository.list_active_device_ids(elder_id)
                saved = await self.repository.create_refresh_request(
                    family_id=family.id,
                    elder_id=elder_id,
                    client_request_id=client_request_id,
                    request_fingerprint=fingerprint,
                    requested_at=requested_at,
                )
                try:
                    saved.device_online = (
                        await self.refresh_notifier.notify_model_usage_report_requested(
                            elder_id,
                            active_device_ids,
                            client_request_id,
                            requested_at,
                        )
                    )
                except Exception:
                    saved.device_online = False
                self.repository.add_refresh_audit(
                    family_id=family.id,
                    elder_id=elder_id,
                    refresh_request_id=saved.id,
                    device_online=saved.device_online,
                )
                return self._refresh_response(saved)

    @staticmethod
    def _validate_idempotency_key(value: str | None, batch_id: UUID) -> None:
        try:
            header_id = UUID(value) if value else None
        except ValueError as exc:
            raise ApiError(400, "INVALID_USAGE_BATCH", "用量批次幂等标识不正确") from exc
        if header_id != batch_id:
            raise ApiError(400, "INVALID_USAGE_BATCH", "用量批次幂等标识不一致")

    @staticmethod
    def _validate_refresh_idempotency_key(
        value: str | None,
        client_request_id: UUID,
    ) -> None:
        try:
            header_id = UUID(value) if value else None
        except ValueError as exc:
            raise ApiError(
                400,
                "REQUEST_VALIDATION_ERROR",
                "幂等请求标识不正确",
            ) from exc
        if header_id != client_request_id:
            raise ApiError(
                400,
                "REQUEST_VALIDATION_ERROR",
                "幂等请求标识不一致",
            )

    @staticmethod
    def _refresh_response(
        request: StoredModelUsageRefreshRequest,
    ) -> ModelUsageRefreshResponse:
        return ModelUsageRefreshResponse(
            client_request_id=UUID(request.client_request_id),
            requested_at=ensure_utc(request.requested_at),
            device_online=request.device_online,
        )

    @staticmethod
    def _require_utc(value: datetime, error_code: str) -> datetime:
        if value.tzinfo is None or value.utcoffset() != timedelta(0):
            raise ApiError(400, error_code, "时间必须使用 UTC")
        return value.astimezone(UTC)

    @staticmethod
    def _fingerprint(request: ModelUsageBatchRequest) -> str:
        encoded = json.dumps(
            request.model_dump(mode="json"),
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        )
        return hashlib.sha256(encoded.encode()).hexdigest()

    @classmethod
    def _aggregate_items(cls, items: list[ModelUsageItemRequest]) -> list[AggregatedUsageItem]:
        grouped: dict[
            tuple[ModelUsageModality, str, str | None, str],
            AggregatedUsageItem,
        ] = {}
        for item in items:
            key = (item.modality, item.provider, item.model, item.feature)
            aggregate = grouped.setdefault(
                key,
                AggregatedUsageItem(
                    modality=item.modality,
                    provider=item.provider,
                    model=item.model,
                    feature=item.feature,
                ),
            )
            aggregate.request_count = cls._checked_sum(aggregate.request_count, item.request_count)
            aggregate.success_count = cls._checked_sum(aggregate.success_count, item.success_count)
            aggregate.input_tokens = cls._checked_sum(aggregate.input_tokens, item.input_tokens)
            aggregate.output_tokens = cls._checked_sum(aggregate.output_tokens, item.output_tokens)
            aggregate.asr_audio_duration_ms = cls._checked_sum(
                aggregate.asr_audio_duration_ms, item.asr_audio_duration_ms
            )
            aggregate.tts_character_count = cls._checked_sum(
                aggregate.tts_character_count, item.tts_character_count
            )
            aggregate.tts_audio_duration_ms = cls._checked_sum(
                aggregate.tts_audio_duration_ms, item.tts_audio_duration_ms
            )
            aggregate.contains_estimated_values |= item.contains_estimated_values
        return list(grouped.values())

    @staticmethod
    def _checked_sum(left: int, right: int) -> int:
        result = left + right
        if result > MAX_USAGE_COUNTER:
            raise ApiError(400, "INVALID_USAGE_BATCH", "聚合后的用量计数超过上限")
        return result

    @staticmethod
    def _empty_totals() -> ModelUsageTotals:
        return ModelUsageTotals(
            input_tokens=0,
            output_tokens=0,
            mllm_request_count=0,
            asr_request_count=0,
            tts_request_count=0,
            asr_audio_duration_ms=0,
            tts_character_count=0,
            tts_audio_duration_ms=0,
            contains_estimated_values=False,
        )

    @staticmethod
    def _accumulate_totals(
        totals: ModelUsageTotals,
        item: ModelUsageItem,
    ) -> None:
        if item.modality == ModelUsageModality.MLLM:
            totals.input_tokens += item.input_tokens
            totals.output_tokens += item.output_tokens
            totals.mllm_request_count += item.request_count
        elif item.modality == ModelUsageModality.ASR:
            totals.asr_request_count += item.request_count
            totals.asr_audio_duration_ms += item.asr_audio_duration_ms
        elif item.modality == ModelUsageModality.TTS:
            totals.tts_request_count += item.request_count
            totals.tts_character_count += item.tts_character_count
            totals.tts_audio_duration_ms += item.tts_audio_duration_ms
        totals.contains_estimated_values |= item.contains_estimated_values

    @staticmethod
    def _first_day_of_next_month(value: date) -> date:
        if value.month == 12:
            return date(value.year + 1, 1, 1)
        return date(value.year, value.month + 1, 1)
