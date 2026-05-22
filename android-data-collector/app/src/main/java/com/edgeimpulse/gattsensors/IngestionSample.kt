package com.edgeimpulse.gattsensors

data class IngestionSample(
    val protected: Map<String, String>,
    val payload: Map<String, Any>
)
