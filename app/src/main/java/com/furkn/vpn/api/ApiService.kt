package com.furkn.vpn.api

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

data class RequestSmsRequest(
    val phone: String,
    val device_id: String,
    val platform: String,
    val app_version: String
)

data class LoginRequest(
    val phone: String,
    val code: String
)

data class BaseResponse(
    val ok: Boolean,
    val message: String? = null,
    val error: String? = null
)

data class LoginResponse(
    val ok: Boolean,
    val user_id: String? = null,
    val phone: String? = null,
    val login_token: String? = null,
    val expires_at: String? = null,
    val message: String? = null,
    val error: String? = null
)

data class BootstrapResponse(
    val session: BootstrapSession?,
    val policy: BootstrapPolicy?,
    val user_access: BootstrapUserAccess?,
    val entries: List<BootstrapEntry>?
)

data class BootstrapSession(
    val config_ttl_sec: Int?
)

data class BootstrapPolicy(
    val selector_policy: SelectorPolicy?
)

data class SelectorPolicy(
    val strategy: String?,
    val fallback_order: List<String>?,
    val connect_timeout_ms: Int?,
    val handshake_timeout_ms: Int?
)

data class BootstrapUserAccess(
    val subscription_status: String?,
    val user_id: String?,
    val plan_code: String?,
    val expires_at: String?,
    val phone: String?,
    val first_name: String?,
    val last_name: String?
)

data class BootstrapEntry(
    val host: String?,
    val port: Int?,
    val transports: List<BootstrapTransport>?
)

data class BootstrapTransport(
    val code: String?,
    val connect_material: ConnectMaterial?
)

data class ConnectMaterial(
    val client_id: String? = null,
    val auth: String? = null
)

interface ApiService {

    @POST("/auth/request-sms")
    suspend fun requestSms(
        @Body request: RequestSmsRequest
    ): BaseResponse

    @POST("/auth/login")
    suspend fun login(
        @Body request: LoginRequest
    ): LoginResponse

    @GET("/bootstrap/{token}")
    suspend fun bootstrap(
        @Path("token") token: String
    ): BootstrapResponse
}