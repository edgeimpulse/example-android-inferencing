package com.edgeimpulse.gattsensors

import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

interface EdgeImpulseService {
    @POST("api/training/data")
    suspend fun uploadSample(
        @Header("x-api-key") apiKey: String,
        @Body body: IngestionSample
    )
}
