package com.rudnam.wallet.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import java.util.Locale

/**
 * The server's messages (refusals, a notification's detail) are written in
 * English. In Japanese, the known ones are translated, as the website does
 * (serverText in web/src/i18n.tsx; this table is jaServer in
 * web/src/locales/ja.ts, and ServerTextTest keeps the two the same). A
 * pattern's {name} matches anything, and {msg} is itself translated.
 */
object ServerText {
    fun translate(message: String, locale: Locale): String {
        if (locale.language != "ja") return message
        JA[message]?.let { return it }
        for (p in patterns) {
            val m = p.regex.matchEntire(message) ?: continue
            return Regex("\\{(\\w+)\\}").replace(p.translated) { r ->
                val name = r.groupValues[1]
                val value = m.groupValues.getOrElse(p.names.indexOf(name) + 1) { "" }
                if (name == "msg") translate(value, locale) else value
            }
        }
        return message
    }

    private class Pattern(val regex: Regex, val names: List<String>, val translated: String)

    private val patterns: List<Pattern> by lazy {
        JA.filterKeys { '{' in it }.map { (en, ja) ->
            val names = mutableListOf<String>()
            val source = en.split(Regex("(?=\\{\\w+\\})|(?<=\\})")).joinToString("") { part ->
                if (Regex("^\\{\\w+\\}$").matches(part)) {
                    names += part.drop(1).dropLast(1)
                    "(.+?)"
                } else {
                    Regex.escape(part)
                }
            }
            Pattern(Regex(source), names, ja)
        }
    }

    internal val JA: Map<String, String> = mapOf(
        "the password is incorrect" to "パスワードが違います",
        "email or password is incorrect" to "メールアドレスかパスワードが違います",
        "an account with this email already exists" to "このメールアドレスのアカウントはすでにあります",
        "enter a valid email address" to "正しいメールアドレスを入力してください",
        "password must be {min} to {max} characters" to "パスワードは{min}〜{max}文字にしてください",
        "sign-up is closed on this server" to "このサーバーでは新規登録を受け付けていません",
        "sign in required" to "ログインしてください",
        "session expired; sign in again" to "セッションの有効期限が切れました。もう一度ログインしてください",
        "too many attempts; wait a minute and try again" to "試行回数が多すぎます。1分ほど待ってからお試しください",
        "that sign-in took too long; try again" to "ログインに時間がかかりすぎました。もう一度お試しください",
        "type your email address to confirm" to "確認のためメールアドレスを入力してください",
        "your email is already confirmed" to "メールアドレスはすでに確認済みです",
        "this server doesn't send email" to "このサーバーはメールを送信しません",
        "the email could not be sent; try again in a few minutes" to "メールを送信できませんでした。数分後にお試しください",
        "this link has expired or was already used; ask for a new one" to "このリンクは期限切れか、すでに使われています。新しいリンクを依頼してください",
        "not found" to "見つかりません",
        "no such category" to "そのカテゴリはありません",
        "no such account" to "その口座はありません",
        "ids must list the accounts in their new order" to "新しい順に口座を指定してください",
        "no such record" to "その記録はありません",
        "those records can no longer be restored" to "これらの記録はもう元に戻せません",
        "no such budget" to "その予算はありません",
        "no such token" to "そのトークンはありません",
        "no such email" to "そのメールはありません",
        "enter an amount above zero" to "0より大きい金額を入力してください",
        "enter a monthly limit above zero" to "0より大きい月の上限を入力してください",
        "enter a date" to "日付を入力してください",
        "starting balance is too large" to "開始時点の残高が大きすぎます",
        "the currency can't change once you have records" to "記録があると通貨は変更できません",
        "currency must be a 3-letter code such as JPY" to "通貨はJPYのような3文字のコードにしてください",
        "a name must be at most 60 characters" to "名前は60文字以内にしてください",
        "you already have a category with that name" to "同じ名前のカテゴリがすでにあります",
        "you already have a account with that name" to "同じ名前の口座がすでにあります",
        "you already have a budget with that name" to "同じ名前の予算がすでにあります",
        "a category name must be 1 to {max} characters" to "カテゴリ名は1〜{max}文字にしてください",
        "a account name must be 1 to {max} characters" to "口座名は1〜{max}文字にしてください",
        "a budget name must be 1 to {max} characters" to "予算名は1〜{max}文字にしてください",
        "payee and note must be at most {max} characters" to "支払先とメモは{max}文字以内にしてください",
        "choose a period of 20 years or less" to "期間は20年以内にしてください",
        "choose 1 to {max} records" to "記録は1〜{max}件選んでください",
        "the file is over {size} MB" to "ファイルが{size}MBを超えています",
        "the email is over {size} MB" to "メールが{size}MBを超えています",
        "that isn't an email. In Gmail, open the email, choose ⋮ → Show original, and copy everything" to "メールの形式ではありません。Gmailでメールを開き、⋮ → メッセージのソースを表示 を選んで全文をコピーしてください",
        "you have {n} tokens already; remove one first" to "トークンはすでに{n}個あります。先に1つ削除してください",
        "add your Claude API key in Settings first" to "先に設定でClaudeのAPIキーを登録してください",
        "that doesn't look like an Anthropic API key; they start with sk-ant-" to "Anthropic APIキーではないようです。キーは sk-ant- で始まります",
        "this server can't store API keys yet: whoever runs it needs to set SECRETS_KEY" to "このサーバーではまだAPIキーを保存できません。運営者がSECRETS_KEYを設定する必要があります",
        "your saved API key can't be read any more; add it again in Settings → Claude" to "保存済みのAPIキーを読み取れなくなりました。設定 → Claude でもう一度登録してください",
        "Claude didn't accept your API key. Check it in Settings → Claude." to "ClaudeがAPIキーを受け付けませんでした。設定 → Claude で確認してください。",
        "Claude is busy right now. Try again in a minute." to "Claudeが混み合っています。1分ほどしてからお試しください。",
        "Claude took too long to answer. Try again." to "Claudeの応答に時間がかかりすぎました。もう一度お試しください。",
        "Your Anthropic account is out of credit. Add some in the Claude Console." to "Anthropicアカウントのクレジットが不足しています。Claude Consoleで追加してください。",
        "{name} is an expense category" to "{name}は支出のカテゴリです",
        "{name} is an income category" to "{name}は収入のカテゴリです",
        "PayPay amounts are in yen, and your main currency is {currency}" to "PayPayの金額は円ですが、メインの通貨が{currency}になっています",
        "IC card amounts are in yen, and your main currency is {currency}" to "交通系ICカードの金額は円ですが、メインの通貨が{currency}になっています",
        "the card's IDm must be 8 bytes in hex" to "カードのIDmが正しくありません",
        "the card has no history" to "カードに履歴がありません",
        "the card and the account top-ups come from must be different" to "カードとチャージの支払い元は別の口座にしてください",
        "a budget needs an expense category" to "予算には支出のカテゴリが必要です",
        "a transfer has no category" to "振替にはカテゴリがありません",
        "a transfer needs two different accounts" to "振替には別々の2つの口座が必要です",
        "only a transfer has a second account" to "2つ目の口座を指定できるのは振替だけです",
        "the file is empty or not a CSV file" to "ファイルが空か、CSVファイルではありません",
        "the file isn't UTF-8 text; use the CSV file as PayPay sent it, not one saved again by Excel" to "ファイルがUTF-8ではありません。Excelで保存し直したものではなく、PayPayから受け取ったままのCSVファイルを使ってください",
        "this doesn't look like a PayPay transaction history: no {col} column" to "PayPayの取引履歴ではないようです（{col}の列がありません）",
        "this doesn't look like a Wallet export: no {col} column" to "Walletの書き出しファイルではないようです（{col}の列がありません）",
        "the demo can't do this; create an account to use it" to "デモではこの操作はできません。使うにはアカウントを作成してください",
        "enter the shop's name" to "お店の名前を入力してください",
        "no such rule" to "その設定はありません",
        "action must be delete, categorize, move, confirm, tag or untag" to "操作は delete、categorize、move、confirm、tag、untag のいずれかにしてください",
        "a tag must be at most {max} characters" to "タグは{max}文字以内にしてください",
        "a record can have at most {max} tags" to "1件の記録に付けられるタグは{max}個までです",
        "enter a tag" to "タグを入力してください",
        "This sign-in link isn't valid. Go back to the app you were connecting and try again." to "このログインリンクは無効です。接続しようとしていたアプリに戻って、もう一度お試しください。",
        "you have {n} connections already; disconnect one in Settings → Claude first" to "接続はすでに{n}個あります。先に 設定 → Claude で1つ解除してください",
        "Failed to fetch" to "サーバーに接続できませんでした",
        "Already recorded, so it wasn't added again." to "記録済みのため、追加しませんでした。",
        "Not from a payment app this server reads." to "このサーバーが読み取る決済アプリの通知ではありません。",
        "No amount, so not a payment." to "金額がないため、支払いではありません。",
        "Not a payment made (declined, a balance or a top-up)." to "支払いではありません（拒否、残高、チャージなど）。",
        "Not a PayPay payment." to "PayPayの支払いではありません。",
        "No amount in the notification." to "通知に金額がありません。",
        "Couldn't read the amount {amount}." to "金額 {amount} を読み取れませんでした。",
        "line {line}: {msg}" to "{line}行目: {msg}",
        "unreadable row" to "読み取れない行",
        "amount is in {a}, not {b}; skipped" to "金額が{b}ではなく{a}のため、スキップしました",
        "amount {amount} is not a number" to "金額 {amount} が数字ではありません",
        "date {date} not understood" to "日付 {date} を読み取れません",
        "no account" to "口座がありません",
        "amount not understood" to "金額を読み取れません",
        "no amount, or both paid and received" to "金額がないか、出金と入金の両方があります",
        "the amounts paid by each method don't add up to {n}" to "支払い方法ごとの金額の合計が{n}になりません",
    )
}

/** A message from the server, in the language the screen is drawn in when it's a known one. */
@Composable
fun serverText(message: String): String = ServerText.translate(message, LocalConfiguration.current.locales[0])
