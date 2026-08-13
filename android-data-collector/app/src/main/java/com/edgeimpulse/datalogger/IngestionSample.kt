package com.edgeimpulse.datalogger

data class IngestionSample(
    val protected: Map<String, String>,
    val payload: Map<String, Any>
)
