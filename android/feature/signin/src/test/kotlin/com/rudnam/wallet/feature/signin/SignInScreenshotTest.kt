package com.rudnam.wallet.feature.signin

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.rudnam.wallet.core.designsystem.theme.WalletTheme
import com.rudnam.wallet.core.model.AuthOptions
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The sign-in page at phone size, into build/outputs/roborazzi (CI's native-screenshots). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class SignInScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val actions = object : SignInActions {
        override fun setMode(mode: SignInMode) {}
        override fun setEmail(email: String) {}
        override fun setPassword(password: String) {}
        override fun submit() {}
        override fun startDemo() {}
        override fun googleUrl() = ""
    }
    private val all = AuthOptions(google = true, demo = true, reset = true)

    private fun shot(name: String, state: SignInUiState, dark: Boolean = false) {
        compose.setContent { WalletTheme(darkTheme = dark) { SignInScreen(state, actions, onGoogle = {}, language = "en", onLanguage = {}) } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/signin-$name.png")
    }

    @Test
    fun signIn() = shot("sign-in", SignInUiState(SignInMode.SignIn, options = all))

    @Test
    fun dark() = shot("sign-in-dark", SignInUiState(SignInMode.SignIn, email = "francis@example.com", password = "secret", options = all), dark = true)

    @Test
    @Config(qualifiers = "ja-w411dp-h891dp-xxhdpi")
    fun japanese() = shot("sign-in-ja", SignInUiState(SignInMode.SignIn, options = all))

    @Test
    fun signUpRefused() = shot(
        "sign-up",
        SignInUiState(SignInMode.SignUp, email = "francis@example.com", options = all, notice = SignInNotice.Server("an account with this email already exists")),
    )

    @Test
    fun forgot() = shot("forgot", SignInUiState(SignInMode.Forgot, email = "francis@example.com", options = all, notice = SignInNotice.ResetSent))

    @Test
    fun emailOnly() = shot("email-only", SignInUiState(SignInMode.SignIn, notice = SignInNotice.Deleted))
}
