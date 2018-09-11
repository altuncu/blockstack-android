package org.blockstack.android.sdk;

import android.support.test.InstrumentationRegistry
import android.support.test.rule.ActivityTestRule
import android.support.test.runner.AndroidJUnit4
import org.blockstack.android.sdk.test.TestActivity
import org.hamcrest.Matchers
import org.junit.Assert
import org.junit.Before
import org.junit.Rule
import org.junit.Test;
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch

@RunWith(AndroidJUnit4::class)
class BlockstackSession2CipherTest {
    private val PRIVATE_KEY = "a5c61c6ca7b3e7e55edee68566aeab22e4da26baa285c7bd10e8d2218aa3b229"
    private val PUBLIC_KEY = "027d28f9951ce46538951e3697c62588a87f1f1f295de4a14fdd4c780fc52cfe69"
    private val DECENTRALIZED_ID = "did:btc-addr:1NZNxhoxobqwsNvTb16pdeiqvFvce3Yg8U"

    @get:Rule
    val rule = ActivityTestRule(TestActivity::class.java)

    lateinit var session: BlockstackSession2
    lateinit var signInSession: BlockstackSession

    private lateinit var userData: UserData

    @Before
    fun setup() {
        val context = InstrumentationRegistry.getContext()
        val latch = CountDownLatch(1)
        val config = "https://flamboyant-darwin-d11c17.netlify.com".toBlockstackConfig(arrayOf())

        // sign in if needed
        rule.activity.runOnUiThread {
            signInSession = BlockstackSession(context, config) {
                signInSession.makeAuthResponse(PUBLIC_KEY) { authResponse ->
                    if (authResponse.hasValue) {
                        signInSession.isUserSignedIn { signedIn ->
                            if (signedIn) {
                                signInSession.loadUserData { it ->
                                    if (it != null) {
                                        userData = it
                                        latch.countDown()
                                    } else {
                                        failTest("loadUserData failed")
                                    }
                                }

                            } else {
                                signInSession.handlePendingSignIn(authResponse.value!!) {
                                    if (it.hasValue) {
                                        userData = it.value!!
                                        latch.countDown()
                                    } else {
                                        failTest("sign in failed")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        latch.await()
        session.signIn(config.appDomain.toString(), userData.appPrivateKey, userData.decentralizedID, userData.json.getString("hubUrl"), "some data")
    }

    private fun failTest(msg: String) {
        rule.activity.runOnUiThread {
            Assert.fail(msg)
        }
    }


    @Test
    fun encryptedStringCanBeDecrypted() {


        // verify user data
        var decryptedContent: Any? = null

        val plainText = "PlainText"
        val cryptoOptions = CryptoOptions(PUBLIC_KEY, PRIVATE_KEY)
        session.encryptContent(plainText, cryptoOptions)

        Assert.assertThat(decryptedContent, Matchers.notNullValue())
        Assert.assertThat(decryptedContent as String, Matchers.`is`(plainText))
    }

    @Test
    fun encryptedByteArrayCanBeDecrypted() {


        // verify user data
        var latch = CountDownLatch(1)
        var decryptedContent: Any? = null

        val plainText = "PlainText"
        val plainByteArray = plainText.toByteArray()
        val cryptoOptions = CryptoOptions(PUBLIC_KEY, PRIVATE_KEY)
        rule.activity.runOnUiThread {
            session.encryptContent(plainByteArray, cryptoOptions)
        }

        latch.await()
        Assert.assertThat(decryptedContent, Matchers.notNullValue())
        Assert.assertThat(String(decryptedContent as ByteArray), Matchers.`is`(plainText))
    }
}