package com.example.silverageassistant.data.middleserver

import com.example.silverageassistant.data.model.ModelRuntimeConfiguration

data class FamilyModelConfigurationUpdateRequest(
    val elderId: String,
    val configuration: ModelRuntimeConfiguration,
    val expectedRevision: Long?,
    val clientRequestId: String,
)

interface FamilyModelConfigurationRepository {
    suspend fun getFamilyModelConfiguration(elderId: String): ModelRuntimeConfiguration?

    suspend fun updateFamilyModelConfiguration(
        request: FamilyModelConfigurationUpdateRequest,
    ): ModelRuntimeConfiguration
}

interface ElderModelConfigurationRepository {
    suspend fun getElderModelConfiguration(): ModelRuntimeConfiguration?
}
