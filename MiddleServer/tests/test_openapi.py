from fastapi import FastAPI
from httpx import AsyncClient

from app.core.config import Settings

ApiFixture = tuple[AsyncClient, FastAPI, Settings]


async def test_openapi_contains_binding_contract(api: ApiFixture) -> None:
    client, _, _ = api
    response = await client.get("/openapi.json")
    assert response.status_code == 200
    document = response.json()
    paths = document["paths"]
    expected = {
        "/api/v1/auth/family/register",
        "/api/v1/auth/refresh",
        "/api/v1/elders",
        "/api/v1/bindings/codes",
        "/api/v1/devices/bind",
        "/api/v1/bindings",
        "/api/v1/elders/{elder_id}/commands/notifications",
        "/api/v1/elders/{elder_id}/commands/reminders",
        "/api/v1/commands/pending",
        "/api/v1/commands/{command_id}/ack",
        "/api/v1/devices/me/family-contacts",
        "/api/v1/elders/{elder_id}/model-config",
        "/api/v1/devices/me/model-config",
        "/api/v1/model-usage/batches",
        "/api/v1/elders/{elder_id}/model-usage",
        "/api/v1/elders/{elder_id}/model-usage/daily",
        "/api/v1/elders/{elder_id}/model-usage/refresh",
        "/api/v1/elders/{elder_id}/safety-monitoring/config",
        "/api/v1/devices/me/safety-monitoring/config",
        "/api/v1/devices/me/safety-events",
        "/api/v1/devices/me/safety-events/{event_id}/image",
        "/api/v1/elders/{elder_id}/safety-events",
        "/api/v1/elders/{elder_id}/safety-events/{event_id}/image",
        "/api/v1/elders/{elder_id}/safety-events/{event_id}/acknowledge",
    }
    assert expected.issubset(paths)
    assert "/api/v1/auth/family/dev-verification-token" not in paths
    register_properties = document["components"]["schemas"]["FamilyRegisterRequest"]["properties"]
    assert "verification_token" not in register_properties
    notification_operation = paths["/api/v1/elders/{elder_id}/commands/notifications"]["post"]
    assert notification_operation["security"] == [{"HTTPBearer": []}]
    idempotency_parameter = next(
        parameter
        for parameter in notification_operation["parameters"]
        if parameter["name"] == "Idempotency-Key"
    )
    assert idempotency_parameter["required"] is True
    notification_schema = document["components"]["schemas"]["NotificationCreateRequest"]
    assert notification_schema["properties"]["content"]["maxLength"] == 200
    contacts_operation = paths["/api/v1/devices/me/family-contacts"]["get"]
    assert contacts_operation["security"] == [{"HTTPBearer": []}]
    assert {"401", "403", "503"}.issubset(contacts_operation["responses"])
    contacts_schema = document["components"]["schemas"]["FamilyContact"]
    assert contacts_schema["properties"]["relationship"]["$ref"].endswith("/Relationship")
    bind_response = document["components"]["schemas"]["DeviceBindResponse"]
    assert bind_response["properties"]["bound_at"]["description"] == "本次设备凭据生效时间"
    model_config_operation = paths["/api/v1/elders/{elder_id}/model-config"]["put"]
    assert model_config_operation["security"] == [{"HTTPBearer": []}]
    assert {"400", "401", "403", "404", "409", "410"}.issubset(model_config_operation["responses"])
    model_config_request = document["components"]["schemas"]["ModelConfigurationUpdateRequest"]
    assert not {
        "api_key",
        "encrypted_api_key",
        "authorization",
        "credential",
    } & set(model_config_request["properties"])
    assert model_config_request["properties"]["reasoning_enabled"]["const"] is False
    assert model_config_request["properties"]["context_window_tokens"]["minimum"] == 1024
    assert model_config_request["properties"]["context_window_tokens"]["maximum"] == 2_000_000
    sampling_schema = document["components"]["schemas"]["ModelSamplingConfiguration"]
    assert sampling_schema["properties"]["temperature"]["minimum"] == 0
    assert sampling_schema["properties"]["temperature"]["maximum"] == 2
    assert sampling_schema["properties"]["top_p"]["minimum"] == 0
    assert sampling_schema["properties"]["top_p"]["maximum"] == 1
    assert sampling_schema["properties"]["top_k"]["minimum"] == 0
    assert sampling_schema["properties"]["top_k"]["maximum"] == 1000
    usage_upload = paths["/api/v1/model-usage/batches"]["post"]
    assert usage_upload["security"] == [{"HTTPBearer": []}]
    assert {"400", "401", "409", "413"}.issubset(usage_upload["responses"])
    usage_idempotency = next(
        parameter
        for parameter in usage_upload["parameters"]
        if parameter["name"] == "Idempotency-Key"
    )
    assert usage_idempotency["required"] is True
    usage_batch = document["components"]["schemas"]["ModelUsageBatchRequest"]
    assert usage_batch["properties"]["items"]["minItems"] == 1
    assert usage_batch["properties"]["items"]["maxItems"] == 100
    assert usage_batch["properties"]["time_zone"]["maxLength"] == 100
    assert usage_batch["properties"]["time_zone_source"]["$ref"].endswith(
        "/ModelUsageTimeZoneSource"
    )
    usage_item = document["components"]["schemas"]["ModelUsageItemRequest"]
    assert usage_item["properties"]["provider"]["maxLength"] == 80
    assert usage_item["properties"]["model"]["anyOf"][0]["maxLength"] == 120
    assert usage_item["properties"]["request_count"]["maximum"] == 9_000_000_000_000_000
    assert not {
        "elder_id",
        "device_id",
        "api_key",
        "prompt",
        "content",
        "audio",
    } & set(usage_batch["properties"])
    usage_query = paths["/api/v1/elders/{elder_id}/model-usage"]["get"]
    assert usage_query["security"] == [{"HTTPBearer": []}]
    assert {"400", "401", "403"}.issubset(usage_query["responses"])
    query_parameters = {parameter["name"]: parameter for parameter in usage_query["parameters"]}
    assert query_parameters["from"]["required"] is True
    assert query_parameters["to"]["required"] is True
    daily_usage = paths["/api/v1/elders/{elder_id}/model-usage/daily"]["get"]
    assert daily_usage["security"] == [{"HTTPBearer": []}]
    assert {"401", "403"}.issubset(daily_usage["responses"])
    daily_response = document["components"]["schemas"]["DailyModelUsageResponse"]
    assert {
        "elder_id",
        "period_started_on",
        "period_ended_on",
        "current_date",
        "timezone",
        "timezone_source",
        "days",
        "last_reported_at",
    } == set(daily_response["properties"])
    usage_refresh = paths["/api/v1/elders/{elder_id}/model-usage/refresh"]["post"]
    assert usage_refresh["security"] == [{"HTTPBearer": []}]
    assert {"400", "401", "403", "409", "429"}.issubset(usage_refresh["responses"])
    refresh_idempotency = next(
        parameter
        for parameter in usage_refresh["parameters"]
        if parameter["name"] == "Idempotency-Key"
    )
    assert refresh_idempotency["required"] is True
    refresh_request = document["components"]["schemas"]["ModelUsageRefreshRequest"]
    assert set(refresh_request["properties"]) == {"client_request_id"}
    refresh_response = document["components"]["schemas"]["ModelUsageRefreshResponse"]
    assert set(refresh_response["properties"]) == {
        "client_request_id",
        "requested_at",
        "device_online",
    }
    safety_config_put = paths["/api/v1/elders/{elder_id}/safety-monitoring/config"]["put"]
    assert safety_config_put["security"] == [{"HTTPBearer": []}]
    safety_config_request = document["components"]["schemas"][
        "SafetyMonitoringConfigurationUpdateRequest"
    ]
    assert "enabled" in safety_config_request["required"]
    assert safety_config_request["properties"]["enabled"]["type"] == "boolean"
    assert safety_config_request["properties"]["interval_minutes"]["minimum"] == 1
    assert safety_config_request["properties"]["interval_minutes"]["maximum"] == 60
    safety_config_response = document["components"]["schemas"][
        "SafetyMonitoringConfigurationResponse"
    ]
    assert set(safety_config_response["required"]) == {
        "enabled",
        "interval_minutes",
        "revision",
        "updated_at",
    }
    safety_event_post = paths["/api/v1/devices/me/safety-events"]["post"]
    assert safety_event_post["security"] == [{"HTTPBearer": []}]
    assert {"400", "401", "409", "410", "429"}.issubset(safety_event_post["responses"])
    safety_event_request = document["components"]["schemas"]["SafetyEventCreateRequest"]
    assert safety_event_request["properties"]["event_summary"]["maxLength"] == 200
    assert set(document["components"]["schemas"]["SafetyEventType"]["enum"]) == {
        "HEALTH_DISCOMFORT_REPORTED",
        "FAMILY_REQUEST",
        "FALL_SUSPECTED",
        "UNCONSCIOUSNESS_SUSPECTED",
        "OTHER_ABNORMALITY",
    }
    assert not {
        "elder_id",
        "device_id",
        "image",
        "api_key",
        "prompt",
        "reasoning",
        "raw_response",
    } & set(safety_event_request["properties"])
    safety_today = paths["/api/v1/elders/{elder_id}/safety-events"]["get"]
    scope = next(
        parameter for parameter in safety_today["parameters"] if parameter["name"] == "scope"
    )
    assert scope["schema"]["const"] == "today"
    safety_response = document["components"]["schemas"]["SafetyEventResponse"]
    assert "acknowledged_by_family_account_id" not in safety_response["properties"]
    assert {
        "image_available",
        "image_content_type",
        "image_byte_size",
    }.issubset(safety_response["required"])
    image_upload = paths["/api/v1/devices/me/safety-events/{event_id}/image"]["put"]
    assert image_upload["security"] == [{"HTTPBearer": []}]
    assert {"image/jpeg", "image/png"} == set(image_upload["requestBody"]["content"])
    image_idempotency = next(
        parameter
        for parameter in image_upload["parameters"]
        if parameter["name"] == "Idempotency-Key"
    )
    assert image_idempotency["required"] is True
    image_download = paths["/api/v1/elders/{elder_id}/safety-events/{event_id}/image"]["get"]
    assert image_download["security"] == [{"HTTPBearer": []}]
    variant = next(
        parameter for parameter in image_download["parameters"] if parameter["name"] == "variant"
    )
    assert set(variant["schema"]["enum"]) == {"thumbnail", "original"}
    assert (
        "中国大陆手机号"
        in document["components"]["schemas"]["FamilyRegisterRequest"]["properties"][
            "mobile_number"
        ]["description"]
    )


async def test_removed_development_verification_endpoint_returns_not_found(
    api: ApiFixture,
) -> None:
    client, _, _ = api
    response = await client.post(
        "/api/v1/auth/family/dev-verification-token",
        json={"mobile_number": "13800138000"},
    )
    assert response.status_code == 404


async def test_validation_errors_do_not_echo_sensitive_input(api: ApiFixture) -> None:
    client, _, _ = api
    response = await client.post(
        "/api/v1/devices/bind",
        json={
            "binding_code": "secret-code",
            "family_mobile_number": "13800138000",
            "elder_display_name": "王阿姨",
            "sharing_consent": True,
            "device_id": "device-001",
            "client_request_id": "not-a-uuid",
        },
    )
    assert response.status_code == 422
    serialized = response.text
    assert "secret-code" not in serialized
    assert "13800138000" not in serialized
    assert response.json()["error"]["code"] == "REQUEST_VALIDATION_ERROR"
