package com.web3auth.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.os.postDelayed
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.web3auth.core.api.ApiHelper
import com.web3auth.core.api.Web3AuthApi
import com.web3auth.core.keystore.KeyStoreManagerUtils
import com.web3auth.core.types.*
import com.web3auth.core.types.Base64
import java8.util.concurrent.CompletableFuture
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.math.BigInteger
import java.util.*

class Web3Auth(web3AuthOptions: Web3AuthOptions) {
    enum class Network {
        @SerializedName("mainnet")
        MAINNET,
        @SerializedName("testnet")
        TESTNET,
        @SerializedName("cyan")
        CYAN
    }

    private val gson = Gson()

    private val sdkUrl = Uri.parse(web3AuthOptions.sdkUrl)
    private val initParams: Map<String, Any>
    private val context : Context

    private var loginCompletableFuture: CompletableFuture<Web3AuthResponse> = CompletableFuture()
    private var logoutCompletableFuture: CompletableFuture<Void> = CompletableFuture()

    private var web3AuthResponse = Web3AuthResponse()
    private var shareMetadata = ShareMetadata()
    private val web3AuthApi = ApiHelper.getInstance().create(Web3AuthApi::class.java)
    private var sessionId: String? = null

    init {
        //initiate keyStore
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            initiateKeyStoreManager()
        }

        // Build init params
        val initParams = mutableMapOf(
            "clientId" to web3AuthOptions.clientId,
            "network" to web3AuthOptions.network.name.lowercase(Locale.ROOT)
        )
        if (web3AuthOptions.redirectUrl != null) initParams["redirectUrl"] = web3AuthOptions.redirectUrl.toString()
        if (web3AuthOptions.whiteLabel != null) initParams["whiteLabel"] = gson.toJson(web3AuthOptions.whiteLabel)
        if (web3AuthOptions.loginConfig != null) initParams["loginConfig"] = gson.toJson(web3AuthOptions.loginConfig)

        this.initParams = initParams
        this.context = web3AuthOptions.context
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun initiateKeyStoreManager() {
        KeyStoreManagerUtils.getKeyGenerator()
    }

    private fun request(path: String, params: LoginParams? = null, extraParams: Map<String, Any>? = null) {
        val paramMap = mapOf(
            "init" to initParams,
            "params" to params
        )
        extraParams?.let{ paramMap.plus("params" to extraParams) }
        val validParams = paramMap.filterValues { it != null }

        val hash = gson.toJson(validParams).toByteArray(Charsets.UTF_8).toBase64URLString()

        val url = Uri.Builder().scheme(sdkUrl.scheme)
            .encodedAuthority(sdkUrl.encodedAuthority)
            .encodedPath(sdkUrl.encodedPath)
            .appendPath(path)
            .fragment(hash)
            .build()

        val defaultBrowser = context.getDefaultBrowser()
        val customTabsBrowsers = context.getCustomTabsBrowsers()

        if (customTabsBrowsers.contains(defaultBrowser)) {
            val customTabs = CustomTabsIntent.Builder().build()
            customTabs.intent.setPackage(defaultBrowser)
            customTabs.launchUrl(context, url)
        } else if (customTabsBrowsers.isNotEmpty()) {
            val customTabs = CustomTabsIntent.Builder().build()
            customTabs.intent.setPackage(customTabsBrowsers[0])
            customTabs.launchUrl(context, url)
        } else {
            // Open in browser externally
            context.startActivity(Intent(Intent.ACTION_VIEW, url))
        }
    }

    fun setResultUrl(uri: Uri?) {
        val hash = uri?.fragment
        if (hash == null) {
            loginCompletableFuture.completeExceptionally(UserCancelledException())
            return
        }
        val error = uri.getQueryParameter("error")
        if (error != null) {
            loginCompletableFuture.completeExceptionally(UnKnownException(error))
        }

        web3AuthResponse = gson.fromJson(
            decodeBase64URLString(hash).toString(Charsets.UTF_8),
            Web3AuthResponse::class.java
        )
        if (web3AuthResponse.error?.isNotBlank() == true ) {
            loginCompletableFuture.completeExceptionally(UnKnownException(web3AuthResponse.error ?: "Something went wrong"))
        }

        if (web3AuthResponse.privKey.isNullOrBlank()) {
            logoutCompletableFuture.complete(null)
        }

        web3AuthResponse.sessionId?.let { KeyStoreManagerUtils.encryptData(KeyStoreManagerUtils.SESSION_ID, it) }
        loginCompletableFuture.complete(web3AuthResponse)
    }

    fun login(loginParams: LoginParams) : CompletableFuture<Web3AuthResponse> {
        //authorize sessionId or login
        authorizeSession(loginParams)

        loginCompletableFuture = CompletableFuture()
        return loginCompletableFuture
    }

    fun logout(params: Map<String, Any>? = null) : CompletableFuture<Void> {
        request("logout", extraParams = params)

        logoutCompletableFuture = CompletableFuture()
        return logoutCompletableFuture
    }

    private fun authorizeSession(loginParams: LoginParams) {
        sessionId = KeyStoreManagerUtils.decryptData(KeyStoreManagerUtils.SESSION_ID)
        if(sessionId != null && sessionId?.isNotEmpty() == true) {
            var pubKey = "04".plus(KeyStoreManagerUtils.getPubKey(sessionId.toString()))
            GlobalScope.launch {
                val result = web3AuthApi.authorizeSession(pubKey)
                if (result.isSuccessful && result.body() != null) {
                    val messageObj = JSONObject(result.body()?.message).toString()
                    shareMetadata = gson.fromJson(
                        messageObj,
                        ShareMetadata::class.java
                    )

                    KeyStoreManagerUtils.savePreferenceData(
                        KeyStoreManagerUtils.EPHEM_PUBLIC_Key,
                        shareMetadata.ephemPublicKey.toString()
                    )
                    KeyStoreManagerUtils.savePreferenceData(
                        KeyStoreManagerUtils.IV_KEY,
                        shareMetadata.iv.toString()
                    )

                    val aes256cbc = AES256CBC(
                        sessionId?.let { it },
                        shareMetadata.ephemPublicKey,
                        shareMetadata.iv.toString()
                    )

                    // Implementation specific oddity - hex string actually gets passed as a base64 string
                    try {
                        val encryptedShareBytes =
                            AES256CBC.toByteArray(BigInteger(shareMetadata.ciphertext, 16))
                        val share = aes256cbc.decrypt(Base64.encodeBytes(encryptedShareBytes))
                        var tempJson = JSONObject(share.toString())
                        tempJson.put("userInfo", tempJson.get("store"))
                        tempJson.remove("store")
                        web3AuthResponse =
                            gson.fromJson(tempJson.toString(), Web3AuthResponse::class.java)
                        if (web3AuthResponse != null) {
                            Handler(Looper.getMainLooper()).postDelayed(500) {
                                loginCompletableFuture.complete(web3AuthResponse)
                            }
                        }
                    } catch (ex: Exception) {
                        ex.printStackTrace()
                    }
                } else {
                    //login with Web3Auth
                    request("login", loginParams)
                }
            }
        } else {
            //login with Web3Auth
            request("login", loginParams)
        }
    }

    fun logout(
        redirectUrl: Uri? = null,
        appState: String? = null
    ) {
        val params = mutableMapOf<String, Any>()
        if (redirectUrl != null) params["redirectUrl"] = redirectUrl.toString()
        if (appState != null) params["appState"] = appState
        logout(params)
    }
}
