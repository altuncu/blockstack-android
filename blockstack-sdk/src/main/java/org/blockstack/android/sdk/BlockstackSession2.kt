package org.blockstack.android.sdk

import android.content.Context
import android.preference.PreferenceManager
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import com.squareup.duktape.Duktape
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import org.jetbrains.anko.coroutines.experimental.bg
import org.json.JSONObject
import java.net.URL
import java.util.*


private val HOSTED_BROWSER_URL_BASE = "https://browser.blockstack.org"

/**
 * Main object to interact with blockstack in an activity
 *
 * The current implementation is a wrapper for blockstack.js using a WebView.
 * This means that methods must be called on the UI thread e.g. using
 * `runOnUIThread`
 *
 * @param config the configuration for blockstack
 * @param onLoadedCallback the callback for when this object is ready to use
 */
class BlockstackSession2(context: Context, private val config: BlockstackConfig,
                         /**
                          * url of the name lookup service, defaults to core.blockstack.org/v1/names
                          */
                         val nameLookupUrl: String = "https://core.blockstack.org/v1/names/") {

    private val TAG = BlockstackSession2::class.qualifiedName

    /**
     * Flag indicating whether this object is ready to use
     */
    var loaded: Boolean = true
        private set(value) {
            field = value
        }

    private var userData: JSONObject? = null
    private var signInCallback: ((Result<UserData>) -> Unit)? = null
    private val lookupProfileCallbacks = HashMap<String, ((Result<Profile>) -> Unit)>()
    private val getFileCallbacks = HashMap<String, ((Result<Any>) -> Unit)>()
    private val putFileCallbacks = HashMap<String, ((Result<String>) -> Unit)>()
    private val sessionStore = SessionStore(PreferenceManager.getDefaultSharedPreferences(context))


    private val duktape = Duktape.create()
    private var blockstack: Blockstack

    init {
        duktape.set("android", JavaScriptInterface2::class.java,
                JavascriptInterface2Object(this))
        duktape.set("console", Console::class.java, object : Console {
            override fun error(msg: String) {
                Log.e(TAG, msg)
            }

            override fun warn(msg: String) {
                Log.w(TAG, msg)
            }

            override fun log(msg: String) {
                Log.i(TAG, msg)
            }

            override fun debug(msg: String) {
                Log.d(TAG, msg)
            }

        })
        duktape.evaluate(context.resources.openRawResource(R.raw.blockstack).bufferedReader().use { it.readText() },
                "error.txt")
        duktape.evaluate(context.resources.openRawResource(R.raw.sessionstore_android).bufferedReader().use { it.readText() },
                "error.txt")
        duktape.evaluate(context.resources.openRawResource(R.raw.blockstack_android2).bufferedReader().use { it.readText() },
                "error.txt")

        blockstack = duktape.get("blockstack", Blockstack::class.java)
        blockstack.newUserSession(config.appDomain.toString())
    }

    internal interface Console {
        fun error(msg: String)
        fun warn(msg: String)
        fun debug(msg: String)
        fun log(msg: String)
    }

    internal interface Blockstack {
        fun newUserSession(appDomain: String)
        fun isUserSignedIn(): Boolean
        fun signIn(privateAppKey: String, privateAppKey1: String, identityAddress: String, hubUrl: String, userDataString: String)
        fun signUserOut()
        fun getFile(path: String, options: String, uniqueIdentifier: String)
        fun putFile(path: String, contentString: String?, options: String, uniqueIdentifier: String, binary: Boolean)
        fun encryptContent(contentString: String?, options: String, binary: Boolean): String
        fun decryptContent(cipherTextString: String?, options: String, binary: Boolean): String
        fun fetchResolve(url: String, response: String)
    }

    /**
     * Check if a user is currently signed in
     *
     * @property callback a function that is called with a flag that is `true` if the user is signed in, `false` if not.
     */
    fun isUserSignedIn(): Boolean {
        val javascript = "isUserSignedIn()"
        Log.d(TAG, javascript)

        ensureLoaded()

        return blockstack.isUserSignedIn()
    }

    fun signIn(appDomain: String, privateAppKey: String, identityAddress: String, hubUrl: String, userDataString: String) {
        blockstack.signIn(appDomain, privateAppKey, identityAddress, hubUrl, userDataString)
    }

    /**
     * Sign the user out
     *
     * @property callback a function that is called after the user is signed out.
     */
    fun signUserOut() {
        val javascript = "signUserOut()"
        Log.d(TAG, javascript)

        ensureLoaded()

        blockstack.signUserOut()
    }

    /**
     * Lookup the profile of a user
     *
     * @param username the registered user name, like `dev_android_sdk.id`
     * @param zoneFileLookupURL the url of the zone file lookup service like `https://core.blockstack.org/v1/names`
     * @param callback is called with the profile of the user or null if not found
     */
    fun lookupProfile(username: String, zoneFileLookupURL: URL? = null, callback: (Result<Profile>) -> Unit) {
        ensureLoaded()
        lookupProfileCallbacks.put(username, callback)
        // TODO
    }

    /* Public storage methods */

    /**
     * Retrieves the specified file from the app's data store.
     *
     * @property path the path of the file from which to read data
     * @property options an instance of a `GetFileOptions` object which is used to configure
     * options such as decryption and reading files from other apps or users.
     * @property callback a function that is called with the file contents. It is not called on the
     * UI thread so you should execute any UI interactions in a `runOnUIThread` block
     */
    fun getFile(path: String, options: GetFileOptions, callback: (Result<Any>) -> Unit) {
        Log.d(TAG, "getFile: path: ${path} options: ${options}")

        ensureLoaded()
        val uniqueIdentifier = addGetFileCallback(callback)
        return blockstack.getFile(path, options.toJSON().toString(), uniqueIdentifier)
    }

    /**
     * Stores the data provided in the app's data store to to the file specified.
     *
     * @property path the path to store the data to
     * @property content the data to store in the file
     * @property options an instance of a `PutFileOptions` object which is used to configure
     * options such as encryption
     * @property callback a function that is called with a `String` representation of a url from
     * which you can read the file that was just put. It is not called on the UI thread so you should
     * execute any UI interactions in a `runOnUIThread` block
     */
    fun putFile(path: String, content: Any, options: PutFileOptions, callback: (Result<String>) -> Unit) {
        Log.d(TAG, "putFile: path: ${path} options: ${options}")

        ensureLoaded()

        val valid = content is String || content is ByteArray
        if (!valid) {
            throw IllegalArgumentException("putFile content only supports String or ByteArray")
        }

        val isBinary = content is ByteArray
        val uniqueIdentifier = addPutFileCallback(callback)

        return if (isBinary) {
            val contentString = Base64.encodeToString(content as ByteArray, Base64.NO_WRAP)
            blockstack.putFile(path, contentString, options.toJSON().toString(), uniqueIdentifier, true)
        } else {
            blockstack.putFile(path, content as String, options.toJSON().toString(), uniqueIdentifier, false)
        }

    }

    /**
     * Encrypt content
     *
     * @plainContent can be a String or ByteArray
     * @options defines how to encrypt
     * @callback called with the cipher object or null if encryption failed
     */
    fun encryptContent(plainContent: Any, options: CryptoOptions): Result<CipherObject> {
        ensureLoaded()

        val valid = plainContent is String || plainContent is ByteArray
        if (!valid) {
            throw IllegalArgumentException("encrypt content only supports String or ByteArray")
        }

        val isBinary = plainContent is ByteArray

        val result = if (isBinary) {
            val contentString = Base64.encodeToString(plainContent as ByteArray, Base64.NO_WRAP)
            Log.d(TAG, "image " + contentString)
            blockstack.encryptContent(contentString, options.toJSON().toString(), true)
        } else {
            blockstack.encryptContent(plainContent as String, options.toJSON().toString(), false)
        }
        if (result != null && !"null".equals(result)) {
            val cipherObject = JSONObject(result)
            return Result(CipherObject(cipherObject))
        } else {
            return Result(null, "failed to encrypt")
        }

    }

    /**
     * Decrypt content
     * @cipherObject can be a String or ByteArray representing the cipherObject returned by  @see encryptContent
     * @options defines how to decrypt the cipherObject
     * @callback called with the plain content as String or ByteArray depending on the given options
     */
    fun decryptContent(cipherObject: Any, options: CryptoOptions, callback: (Result<Any>) -> Unit) {
        ensureLoaded()

        val valid = cipherObject is String || cipherObject is ByteArray
        if (!valid) {
            throw IllegalArgumentException("decrypt content only supports JSONObject or ByteArray")
        }

        val isBinary = cipherObject is ByteArray

        var wasString: Boolean

        val plainContent = if (isBinary) {
            val cipherTextString = Base64.encodeToString(cipherObject as ByteArray, Base64.NO_WRAP)
            wasString = JSONObject(cipherTextString).getBoolean("wasString")
            blockstack.decryptContent(cipherTextString, options.toJSON().toString(), true)
        } else {
            wasString = JSONObject(cipherObject as String).getBoolean("wasString")

            blockstack.decryptContent(cipherObject, options.toJSON().toString(), false)
        }


        if (plainContent != null && !"null".equals(plainContent)) {

            if (wasString) {
                callback(Result(plainContent.removeSurrounding("\"")))
            } else {
                callback(Result(Base64.decode(plainContent, Base64.DEFAULT)))
            }
        } else {
            callback(Result(null, "failed to decrypt"))
        }
    }

    private fun addGetFileCallback(callback: (Result<Any>) -> Unit): String {
        val uniqueIdentifier = UUID.randomUUID().toString()
        getFileCallbacks[uniqueIdentifier] = callback
        return uniqueIdentifier
    }

    private fun addPutFileCallback(callback: (Result<String>) -> Unit): String {
        val uniqueIdentifier = UUID.randomUUID().toString()
        putFileCallbacks[uniqueIdentifier] = callback
        return uniqueIdentifier
    }

    private fun ensureLoaded() {
        if (!this.loaded) {
            throw IllegalStateException("Blockstack session hasn't finished loading." +
                    " Please wait until the onLoadedCallback() has fired before performing operations.")
        }
    }

    @Suppress("unused")
    private interface JavaScriptInterface2 {
        fun lookupProfileResult(username: String, userDataString: String)
        fun lookupProfileFailure(username: String, error: String)
        fun getFileResult(content: String, uniqueIdentifier: String, isBinary: Boolean)
        fun getFileFailure(error: String, uniqueIdentifier: String)
        fun putFileResult(readURL: String, uniqueIdentifier: String)
        fun putFileFailure(error: String, uniqueIdentifier: String)
        fun getSessionData(): String
        fun setSessionData(sessionData: String)
        fun deleteSessionData()
        fun fetch2(url: String, options: String)
    }

    private class JavascriptInterface2Object(private val session: BlockstackSession2) : JavaScriptInterface2 {

        @JavascriptInterface
        override fun lookupProfileResult(username: String, userDataString: String) {
            val userData = JSONObject(userDataString)
            session.lookupProfileCallbacks[username]?.invoke(Result(Profile(userData)))
        }

        @JavascriptInterface
        override fun lookupProfileFailure(username: String, error: String) {
            session.lookupProfileCallbacks[username]?.invoke(Result(null, error))
        }

        @JavascriptInterface
        override fun getFileResult(content: String, uniqueIdentifier: String, isBinary: Boolean) {
            Log.d(session.TAG, "putFileResult")

            if (isBinary) {
                val binaryContent: ByteArray = Base64.decode(content, Base64.DEFAULT)
                session.getFileCallbacks[uniqueIdentifier]?.invoke(Result(binaryContent))
            } else {
                session.getFileCallbacks[uniqueIdentifier]?.invoke(Result(content))
            }
            session.getFileCallbacks.remove(uniqueIdentifier)
        }

        @JavascriptInterface
        override fun getFileFailure(error: String, uniqueIdentifier: String) {
            session.getFileCallbacks[uniqueIdentifier]?.invoke(Result(null, error))
            session.getFileCallbacks.remove(uniqueIdentifier)
        }

        @JavascriptInterface
        override fun putFileResult(readURL: String, uniqueIdentifier: String) {
            Log.d(session.TAG, "putFileResult")

            session.putFileCallbacks[uniqueIdentifier]?.invoke(Result(readURL))
            session.putFileCallbacks.remove(uniqueIdentifier)
        }

        @JavascriptInterface
        override fun putFileFailure(error: String, uniqueIdentifier: String) {
            session.putFileCallbacks[uniqueIdentifier]?.invoke(Result(null, error))
            session.putFileCallbacks.remove(uniqueIdentifier)
        }

        @JavascriptInterface
        override fun getSessionData(): String {
            return session.sessionStore.sessionData.json.toString()
        }

        @JavascriptInterface
        override fun setSessionData(sessionData: String) {
            session.sessionStore.sessionData = SessionData(JSONObject(sessionData))
        }

        @JavascriptInterface
        override fun deleteSessionData() {
            return session.sessionStore.deleteSessionData()
        }

        private val httpClient = OkHttpClient()

        @JavascriptInterface
        override fun fetch2(url: String, optionsString: String) {
            val options = JSONObject(optionsString)

            val builder = Request.Builder()
                    .url(url)

            if (options.has("method")) {
                var body: RequestBody? = null
                if (options.has("body")) {
                    body = RequestBody.create(null, options.getString("body"))
                }
                builder.method(options.getString("method"), body)
            }

            if (options.has("headers")) {
                val headers = options.getJSONObject("headers")
                for (key in headers.keys()) {
                    builder.header(key, headers.getString(key))
                }
            }


            bg {
                try {
                    val response = httpClient.newCall(builder.build()).execute()
                    Log.d(TAG, "response " + response)
                    session.blockstack.fetchResolve(url, response.toJSONString())
                } catch (e:Exception) {
                    Log.e(TAG, e.message, e)
                }
            }

        }

    }
}

fun Response.toJSONString(): String {
    return JSONObject()
            .put("status", code())
            .put("body", body()?.string()?:"")
            .toString()
}