package com.rudnam.wallet.feature.activity

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.rudnam.wallet.core.designsystem.theme.WalletTheme
import com.rudnam.wallet.core.model.BulkChange
import com.rudnam.wallet.core.model.RecordFilter
import com.rudnam.wallet.core.model.SetAsideRecord
import com.rudnam.wallet.core.model.Tag
import com.rudnam.wallet.core.model.Transaction
import com.rudnam.wallet.core.model.TransactionStatus
import com.rudnam.wallet.core.testing.SampleWallet
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.YearMonth
import java.util.TimeZone

/** Activity at phone size, into build/outputs/roborazzi (CI's native-screenshots). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h1600dp-xxhdpi")
class ActivityScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun tokyo() = TimeZone.setDefault(TimeZone.getTimeZone(SampleWallet.zone))

    private val nothing = object : ActivityActions {
        override fun refresh(userAsked: Boolean) {}
        override fun previousMonth() {}
        override fun nextMonth() {}
        override fun setAccount(id: Long?) {}
        override fun setCategory(id: Long?) {}
        override fun search(query: String) {}
        override fun setTag(tag: String?) {}
        override fun clearSearch() {}
        override fun pickCategory(record: Long, category: Long?) {}
        override fun setRemember(record: Long, on: Boolean) {}
        override fun confirm(item: ReviewItem) {}
        override fun confirmAll() {}
        override fun deleteDuplicate(item: ReviewItem) {}
        override fun startChoosing() {}
        override fun toggle(id: Long) {}
        override fun chooseAll(all: Boolean) {}
        override fun bulk(change: BulkChange) {}
    }
    private val navigation = object : ActivityNavigation {
        override fun openRecord(record: Transaction) {}
        override fun addRecord() {}
        override fun openDuplicates() {}
        override fun back() {}
    }

    /** Two to check: one ready (a shop's category), one a possible duplicate of a record already there. */
    private val review = listOf(
        ReviewItem(
            SampleWallet.records.first { it.id == 19L }.copy(id = 31, status = TransactionStatus.Review, payee = "Doutor Kanda", sourceLabel = "Mastercard 1234"),
            categoryId = 13, overriding = false, remember = false, match = null,
        ),
        ReviewItem(
            SampleWallet.records.first { it.id == 16L }.copy(id = 32, status = TransactionStatus.Review, duplicateOfId = 16),
            categoryId = 11, overriding = false, remember = false, match = SampleWallet.records.first { it.id == 16L },
        ),
    )

    private fun state(selected: Set<Long>? = null, filter: RecordFilter = RecordFilter(), caught: Int = 0) = ActivityUiState.Ready(
        wallet = SampleWallet.wallet,
        month = YearMonth.of(2026, 11),
        filter = filter,
        records = SampleWallet.records.filter { it.confirmed || it.status != TransactionStatus.Review },
        capped = false,
        searchOffline = false,
        spent = 19_570,
        income = 280_000,
        tags = listOf(Tag("dinner", 4), Tag("trip", 2)),
        toReview = if (selected == null && !filter.everyMonth) review else emptyList(),
        selected = selected,
        refreshing = false,
        busy = false,
        caught = caught,
    )

    private fun shot(name: String, state: ActivityUiState.Ready, dark: Boolean = false) {
        compose.setContent { WalletTheme(darkTheme = dark) { ActivityScreen(state, nothing, navigation, now = SampleWallet.now) } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/activity-$name.png")
    }

    @Test
    fun month() = shot("month", state())

    @Test
    fun dark() = shot("month-dark", state(), dark = true)

    @Test
    @Config(qualifiers = "ja-w411dp-h1600dp-xxhdpi")
    fun japanese() = shot("month-ja", state())

    @Test
    fun choosing() = shot("choosing", state(selected = setOf(18L, 16L)))

    @Test
    fun tag() = shot("tag", state(filter = RecordFilter(tag = "dinner")))

    @Test
    fun caught() = shot("caught", state(caught = 2))

    private val original = SampleWallet.records.first { it.id == 16L }
    private val duplicates = DuplicatesUiState.Ready(
        wallet = SampleWallet.wallet,
        records = listOf(
            SetAsideRecord(original.copy(id = 40, status = TransactionStatus.Review, duplicateOfId = 16, sourceLabel = "PayPay"), SampleWallet.now, original),
            SetAsideRecord(original.copy(id = 41, status = TransactionStatus.Review, duplicateOfId = 99, payee = "Lawson", amount = 640), SampleWallet.now, null),
        ),
        offline = false,
        busy = false,
    )
    private val noDuplicateActions = object : DuplicatesActions {
        override fun load() {}
        override fun bringBack(record: SetAsideRecord) {}
    }

    private fun duplicatesShot(name: String, dark: Boolean = false) {
        compose.setContent { WalletTheme(darkTheme = dark) { DuplicatesScreen(duplicates, noDuplicateActions, onBack = {}, now = SampleWallet.now) } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/activity-$name.png")
    }

    @Test
    fun duplicatesPage() = duplicatesShot("duplicates")

    @Test
    fun duplicatesDark() = duplicatesShot("duplicates-dark", dark = true)

    @Test
    @Config(qualifiers = "ja-w411dp-h1600dp-xxhdpi")
    fun duplicatesJapanese() = duplicatesShot("duplicates-ja")
}
