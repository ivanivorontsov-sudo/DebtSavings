package com.example.debtsavings

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * GitHub backup/sync for DebtSavings.
 *
 * The access token and local copy of the sync passphrase are encrypted with Android Keystore.
 * The cloud file itself is encrypted with AES-256-GCM and a password-derived key, so the
 * private GitHub repository never receives readable financial data.
 */
object GitHubSyncSettings {
    const val PREFS_NAME = "debt_savings_github_sync"
    private const val KEY_TOKEN = "token_keystore"
    private const val KEY_PASSWORD = "password_keystore"
    private const val KEY_REPO = "repo_name"
    const val KEY_LAST_SHA = "last_remote_sha"
    const val KEY_LAST_DIGEST = "last_local_digest"
    const val KEY_STATUS = "sync_status"
    const val KEY_LAST_SYNC = "last_sync_at"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isConfigured(context: Context): Boolean =
        !readToken(context).isNullOrBlank() && !readPassword(context).isNullOrBlank()

    fun repoName(context: Context): String =
        prefs(context).getString(KEY_REPO, "debtsavings-sync") ?: "debtsavings-sync"

    fun readToken(context: Context): String? = decryptSetting(context, KEY_TOKEN, "github-token")
    fun readPassword(context: Context): String? = decryptSetting(context, KEY_PASSWORD, "sync-password")

    private fun decryptSetting(context: Context, key: String, purpose: String): String? {
        val blob = prefs(context).getString(key, null) ?: return null
        return try {
            String(KeystoreBox.decrypt(blob, purpose), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    fun save(context: Context, token: String, repo: String, password: String) {
        require(token.isNotBlank()) { "Введите GitHub-токен" }
        require(repo.matches(Regex("[A-Za-z0-9_.-]{1,100}")) && !repo.startsWith(".")) {
            "Имя репозитория должно содержать только латинские буквы, цифры, точку, дефис или подчёркивание"
        }
        require(password.length >= 8) { "Пароль синхронизации должен содержать не менее 8 символов" }

        val editor = prefs(context).edit()
            .putString(KEY_TOKEN, KeystoreBox.encrypt(token.trim().toByteArray(Charsets.UTF_8), "github-token"))
            .putString(KEY_PASSWORD, KeystoreBox.encrypt(password.toByteArray(Charsets.UTF_8), "sync-password"))
            .putString(KEY_REPO, repo.trim())
            .remove(KEY_LAST_SHA)
            .remove(KEY_LAST_DIGEST)
            .putString(KEY_STATUS, "Подключение сохранено. Выполняется первая проверка GitHub.")
        check(editor.commit()) { "Не удалось сохранить настройки синхронизации" }
    }

    fun setStatus(context: Context, status: String) {
        prefs(context).edit().putString(KEY_STATUS, status.take(240)).apply()
    }

    fun status(context: Context): String =
        prefs(context).getString(KEY_STATUS, "Синхронизация ещё не запускалась") ?: ""

    fun lastSync(context: Context): Long = prefs(context).getLong(KEY_LAST_SYNC, 0L)

    fun saveBaseline(context: Context, sha: String, digest: String, status: String) {
        prefs(context).edit()
            .putString(KEY_LAST_SHA, sha)
            .putString(KEY_LAST_DIGEST, digest)
            .putString(KEY_STATUS, status.take(240))
            .putLong(KEY_LAST_SYNC, System.currentTimeMillis())
            .apply()
    }

    fun markConflict(context: Context, status: String) {
        setStatus(context, status)
    }

    fun disconnect(context: Context) {
        prefs(context).edit().clear().commit()
        GitHubSyncScheduler.cancel(context)
    }
}

private object KeystoreBox {
    private const val ALIAS = "DebtSavings.GitHubSync.v1"

    private fun key(): javax.crypto.SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? javax.crypto.SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    fun encrypt(plain: ByteArray, purpose: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(purpose.toByteArray(Charsets.UTF_8))
        val result = cipher.iv + cipher.doFinal(plain)
        return SyncBase64.encode(result)
    }

    fun decrypt(encoded: String, purpose: String): ByteArray {
        val blob = SyncBase64.decode(encoded)
        require(blob.size >= 12 + 16) { "Повреждены настройки GitHub" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, 12))
        cipher.updateAAD(purpose.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(blob, 12, blob.size - 12)
    }
}

/** Small Base64 implementation to keep the sync module compatible with minSdk 24 and JVM tests. */
internal object SyncBase64 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private val reverse = IntArray(128) { -1 }.also { map ->
        ALPHABET.forEachIndexed { index, char -> map[char.code] = index }
    }

    fun encode(bytes: ByteArray): String {
        val out = StringBuilder(((bytes.size + 2) / 3) * 4)
        var i = 0
        while (i < bytes.size) {
            val a = bytes[i].toInt() and 0xff
            val hasB = i + 1 < bytes.size
            val hasC = i + 2 < bytes.size
            val b = if (hasB) bytes[i + 1].toInt() and 0xff else 0
            val c = if (hasC) bytes[i + 2].toInt() and 0xff else 0
            out.append(ALPHABET[a ushr 2])
            out.append(ALPHABET[((a and 0x03) shl 4) or (b ushr 4)])
            out.append(if (hasB) ALPHABET[((b and 0x0f) shl 2) or (c ushr 6)] else '=')
            out.append(if (hasC) ALPHABET[c and 0x3f] else '=')
            i += 3
        }
        return out.toString()
    }

    fun decode(value: String): ByteArray {
        val clean = value.filterNot { it.isWhitespace() }
        require(clean.length % 4 == 0) { "Некорректный Base64" }
        val out = ByteArrayOutputStream(clean.length * 3 / 4)
        var i = 0
        while (i < clean.length) {
            val c0 = index(clean[i])
            val c1 = index(clean[i + 1])
            val c2 = if (clean[i + 2] == '=') 0 else index(clean[i + 2])
            val c3 = if (clean[i + 3] == '=') 0 else index(clean[i + 3])
            out.write((c0 shl 2) or (c1 ushr 4))
            if (clean[i + 2] != '=') out.write(((c1 and 0x0f) shl 4) or (c2 ushr 2))
            if (clean[i + 3] != '=') out.write(((c2 and 0x03) shl 6) or c3)
            i += 4
        }
        return out.toByteArray()
    }

    private fun index(char: Char): Int {
        require(char.code < reverse.size && reverse[char.code] >= 0) { "Некорректный Base64" }
        return reverse[char.code]
    }
}

/** AES-256-GCM; PBKDF2-HMAC-SHA256 is implemented with the platform HMAC primitive. */
internal object GitHubSyncCrypto {
    private const val FORMAT = "DebtSavings-GitHub-Encrypted"
    private const val VERSION = 1
    private const val ITERATIONS = 600_000
    private const val KEY_BYTES = 32
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private val aad = "DebtSavings|GitHubSync|v1".toByteArray(Charsets.UTF_8)
    private val random = SecureRandom()

    fun encrypt(plain: ByteArray, password: String): ByteArray {
        require(password.length >= 8) { "Пароль синхронизации слишком короткий" }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val key = pbkdf2(password, salt, ITERATIONS, KEY_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        val encrypted = cipher.doFinal(plain)

        return JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("kdf", "PBKDF2-HMAC-SHA256")
            .put("iterations", ITERATIONS)
            .put("salt", SyncBase64.encode(salt))
            .put("nonce", SyncBase64.encode(nonce))
            .put("ciphertext", SyncBase64.encode(encrypted))
            .toString()
            .toByteArray(Charsets.UTF_8)
    }

    fun decrypt(blob: ByteArray, password: String): ByteArray {
        val root = JSONObject(String(blob, Charsets.UTF_8))
        require(root.optString("format") == FORMAT && root.optInt("version") == VERSION) {
            "Файл GitHub не является резервной копией DebtSavings"
        }
        val iterations = root.optInt("iterations", -1)
        require(iterations in 100_000..2_000_000) { "Некорректные параметры шифрования" }
        val salt = SyncBase64.decode(root.getString("salt"))
        val nonce = SyncBase64.decode(root.getString("nonce"))
        val encrypted = SyncBase64.decode(root.getString("ciphertext"))
        require(salt.size == SALT_BYTES && nonce.size == NONCE_BYTES && encrypted.size >= 16) {
            "Повреждённая облачная копия"
        }
        val key = pbkdf2(password, salt, iterations, KEY_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        return try {
            cipher.doFinal(encrypted)
        } catch (_: Exception) {
            throw IllegalArgumentException("Не удалось расшифровать копию. Проверьте пароль синхронизации.")
        }
    }

    fun sha256(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    private fun pbkdf2(password: String, salt: ByteArray, iterations: Int, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val output = ByteArray(length)
        val blocks = (length + 31) / 32
        var outOffset = 0
        for (block in 1..blocks) {
            val suffix = byteArrayOf(
                (block ushr 24).toByte(),
                (block ushr 16).toByte(),
                (block ushr 8).toByte(),
                block.toByte()
            )
            var u = mac.doFinal(salt + suffix)
            val t = u.copyOf()
            for (round in 1 until iterations) {
                u = mac.doFinal(u)
                for (i in t.indices) t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
            }
            val count = minOf(t.size, length - outOffset)
            t.copyInto(output, outOffset, 0, count)
            outOffset += count
        }
        return output
    }
}

private data class GitHubRemoteFile(val sha: String, val bytes: ByteArray)
private data class GitHubHttpResult(val code: Int, val body: String)

private class GitHubApiException(val statusCode: Int, message: String) : IOException(message)

/** Minimal GitHub Contents API client. It only touches the app's encrypted backup file. */
private class GitHubSyncApi(
    private val token: String,
    private val repo: String
) {
    private var owner: String = ""
    private var branch: String = "main"

    fun prepare() {
        val userResponse = request("GET", "/user")
        requireSuccess(userResponse, "Проверка GitHub-токена")
        owner = JSONObject(userResponse.body).optString("login")
        if (owner.isBlank()) throw IOException("GitHub не вернул имя владельца аккаунта")

        var repoResponse = request("GET", "/repos/$owner/$repo")
        if (repoResponse.code == 404) {
            val body = JSONObject()
                .put("name", repo)
                .put("private", true)
                .put("auto_init", true)
                .put("description", "Зашифрованная синхронизация DebtSavings")
                .toString()
            repoResponse = request("POST", "/user/repos", body)
            if (repoResponse.code !in 200..299) {
                // The user may have created the private repository manually with a narrower token.
                val retry = request("GET", "/repos/$owner/$repo")
                if (retry.code !in 200..299) {
                    requireSuccess(repoResponse, "Создание приватного репозитория")
                } else {
                    repoResponse = retry
                }
            }
        }
        requireSuccess(repoResponse, "Открытие репозитория")
        branch = JSONObject(repoResponse.body).optString("default_branch", "main").ifBlank { "main" }
    }

    fun readFile(path: String): GitHubRemoteFile? {
        val response = request("GET", "/repos/$owner/$repo/contents/${encodePath(path)}?ref=${encode(branch)}")
        if (response.code == 404) return null
        requireSuccess(response, "Чтение облачной копии")
        val json = JSONObject(response.body)
        val encoded = json.optString("content")
        if (encoded.isBlank()) throw IOException("GitHub вернул пустой файл. Проверьте размер облачной копии.")
        return GitHubRemoteFile(
            sha = json.getString("sha"),
            bytes = SyncBase64.decode(encoded)
        )
    }

    fun writeFile(path: String, bytes: ByteArray, sha: String? = null): String {
        val body = JSONObject()
            .put("message", "DebtSavings: синхронизация ${path.substringAfterLast('/')}")
            .put("content", SyncBase64.encode(bytes))
            .put("branch", branch)
        if (!sha.isNullOrBlank()) body.put("sha", sha)

        val response = request(
            "PUT",
            "/repos/$owner/$repo/contents/${encodePath(path)}",
            body.toString()
        )
        if (response.code == 409 || response.code == 422) {
            throw GitHubApiException(response.code, "Облачная копия изменилась одновременно на другом устройстве. Повторите синхронизацию.")
        }
        requireSuccess(response, "Запись облачной копии")
        return JSONObject(response.body).getJSONObject("content").getString("sha")
    }

    private fun request(method: String, path: String, body: String? = null): GitHubHttpResult {
        val connection = (URL("https://api.github.com$path").openConnection() as HttpURLConnection)
        connection.requestMethod = method
        connection.connectTimeout = 15_000
        connection.readTimeout = 25_000
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        connection.setRequestProperty("User-Agent", "DebtSavings-Android")
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            GitHubHttpResult(code, text)
        } finally {
            connection.disconnect()
        }
    }

    private fun requireSuccess(response: GitHubHttpResult, action: String) {
        if (response.code in 200..299) return
        val message = try {
            JSONObject(response.body).optString("message").takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
        val hint = when (response.code) {
            401 -> " Проверьте токен."
            403 -> " Проверьте срок действия токена и права Contents: read/write; для авто-создания репозитория нужны права Administration: read/write."
            404 -> " Репозиторий не найден или токен не имеет к нему доступа."
            429 -> " GitHub временно ограничил частоту запросов."
            else -> ""
        }
        throw GitHubApiException(response.code, "$action: ${message ?: "HTTP ${response.code}"}.$hint")
    }

    private fun encode(value: String) =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun encodePath(path: String) =
        path.split('/').joinToString("/") { encode(it) }
}

/**
 * Three-way snapshot sync:
 * - if only the phone changed, upload it;
 * - if only GitHub changed, restore it;
 * - if both changed, stop and let the user choose a direction.
 * Before a manual overwrite, the previous cloud/local copy is preserved.
 */
internal object GitHubSyncEngine {
    private const val CLOUD_FILE = "debtsavings.enc"

    fun sync(context: Context, mode: String): String = synchronized(this) {
        if (!GitHubSyncSettings.isConfigured(context)) {
            GitHubSyncSettings.setStatus(context, "Сначала подключите GitHub в меню приложения.")
            return@synchronized "GitHub не подключён"
        }
        val token = GitHubSyncSettings.readToken(context) ?: throw IOException("Нужно повторно ввести GitHub-токен")
        val password = GitHubSyncSettings.readPassword(context) ?: throw IOException("Нужно повторно ввести пароль синхронизации")
        val repo = GitHubSyncSettings.repoName(context)
        val api = GitHubSyncApi(token, repo)
        api.prepare()

        val localPlain = buildLocalSnapshot(context)
        val localDigest = snapshotDigest(localPlain)
        val remote = api.readFile(CLOUD_FILE)

        when (mode) {
            "upload" -> upload(context, api, remote, localPlain, localDigest, password)
            "download" -> download(context, remote, localPlain, localDigest, password)
            else -> automatic(context, api, remote, localPlain, localDigest, password)
        }
    }

    private fun automatic(
        context: Context,
        api: GitHubSyncApi,
        remote: GitHubRemoteFile?,
        localPlain: ByteArray,
        localDigest: String,
        password: String
    ): String {
        if (remote == null) {
            return upload(context, api, null, localPlain, localDigest, password)
        }

        val prefs = context.getSharedPreferences(GitHubSyncSettings.PREFS_NAME, Context.MODE_PRIVATE)
        val baseSha = prefs.getString(GitHubSyncSettings.KEY_LAST_SHA, null).orEmpty()
        val baseDigest = prefs.getString(GitHubSyncSettings.KEY_LAST_DIGEST, null).orEmpty()

        if (baseSha.isBlank()) {
            if (isEmptySnapshot(localPlain)) {
                return download(context, remote, localPlain, localDigest, password)
            }
            val message = "В облаке уже есть данные. Выберите в меню: загрузить данные телефона или восстановить копию GitHub."
            GitHubSyncSettings.markConflict(context, message)
            return message
        }

        if (remote.sha == baseSha) {
            if (localDigest == baseDigest) {
                val status = "Синхронизация актуальна."
                GitHubSyncSettings.saveBaseline(context, remote.sha, localDigest, status)
                return status
            }
            return upload(context, api, remote, localPlain, localDigest, password)
        }

        if (localDigest == baseDigest) {
            return download(context, remote, localPlain, localDigest, password)
        }

        val message = "Обнаружен конфликт: изменения есть и на телефоне, и в GitHub. Выберите направление синхронизации в меню."
        GitHubSyncSettings.markConflict(context, message)
        return message
    }

    private fun upload(
        context: Context,
        api: GitHubSyncApi,
        remote: GitHubRemoteFile?,
        localPlain: ByteArray,
        localDigest: String,
        password: String
    ): String {
        if (remote != null) {
            // Preserve the previous encrypted cloud version before a manual overwrite.
            api.writeFile("conflicts/cloud-before-${System.currentTimeMillis()}.enc", remote.bytes)
        }
        val encrypted = GitHubSyncCrypto.encrypt(localPlain, password)
        val sha = api.writeFile(CLOUD_FILE, encrypted, remote?.sha)
        val status = "Загружено в приватный GitHub-репозиторий. Предыдущая облачная версия сохранена при замене."
        GitHubSyncSettings.saveBaseline(context, sha, localDigest, status)
        return status
    }

    private fun download(
        context: Context,
        remote: GitHubRemoteFile?,
        localPlain: ByteArray,
        localDigest: String,
        password: String
    ): String {
        val cloud = remote ?: throw IOException("В GitHub пока нет резервной копии")
        // Keep the current phone data as an encrypted local recovery point before restoring.
        preserveLocalCopy(context, localPlain, password)
        val plain = GitHubSyncCrypto.decrypt(cloud.bytes, password)
        val root = JSONObject(String(plain, Charsets.UTF_8))
        require(root.optString("appId") == "com.example.debtsavings") {
            "В выбранном GitHub-репозитории лежит копия другого приложения"
        }
        val snapshot = DataBackup.parseJson(String(plain, Charsets.UTF_8))
        val dataPrefs = context.getSharedPreferences(Persistence.PREFS_NAME, Context.MODE_PRIVATE)
        DataBackup.apply(dataPrefs, snapshot)
        InterestAlarmScheduler.scheduleNext(context)
        SubscriptionAlarmScheduler.scheduleNext(context)
        PaymentReminderScheduler.scheduleNext(context)

        val restoredJson = buildLocalSnapshot(context)
        val restoredDigest = GitHubSyncCrypto.sha256(restoredJson)
        val status = "Восстановлено из GitHub. Локальная копия до восстановления сохранена в памяти приложения."
        GitHubSyncSettings.saveBaseline(context, cloud.sha, restoredDigest, status)
        return status
    }

    private fun preserveLocalCopy(context: Context, localPlain: ByteArray, password: String) {
        val directory = java.io.File(context.filesDir, "github-sync-recovery")
        if (!directory.exists() && !directory.mkdirs()) throw IOException("Не удалось создать папку локальной копии")
        val file = java.io.File(directory, "before-restore-${System.currentTimeMillis()}.enc")
        file.writeBytes(GitHubSyncCrypto.encrypt(localPlain, password))
    }

    private fun buildLocalSnapshot(context: Context): ByteArray {
        val prefs = context.getSharedPreferences(Persistence.PREFS_NAME, Context.MODE_PRIVATE)
        val credits = mutableListOf<Credit>()
        val transactions = mutableListOf<Transaction>()
        val subscriptions = mutableListOf<Subscription>()
        Persistence.loadCredits(prefs, credits)
        Persistence.loadTransactions(prefs, transactions)
        Persistence.loadSubscriptions(prefs, subscriptions)
        val snapshot = DataBackup.Snapshot(
            credits = credits,
            transactions = transactions,
            subscriptions = subscriptions,
            savingsRate = prefs.getFloat("savings_rate", 0f),
            savingsLastInterestDate = prefs.getLong("savings_last_interest_date", 0L),
            defaultsSeeded = prefs.getBoolean(Persistence.DEFAULTS_SEEDED_KEY, false),
            savingsGoalTarget = prefs.getFloat("savings_goal_target", 0f),
            savingsMonthlyContribution = prefs.getFloat("savings_monthly_contribution", 0f),
            monthlyEssentialExpenses = prefs.getFloat("monthly_essential_expenses", 0f),
            emergencyMonths = prefs.getInt("emergency_months", 3),
            debtPlanExtra = prefs.getFloat("debt_plan_extra", 0f),
            gameModeEnabled = prefs.getBoolean(GameScenario.PREF_ENABLED, false),
            notificationsEnabled = prefs.getBoolean(NotificationPreferences.PREF_ENABLED, true)
        )
        return DataBackup.buildJson(prefs, snapshot).toByteArray(Charsets.UTF_8)
    }

    private fun snapshotDigest(plain: ByteArray): String {\n        val root = JSONObject(String(plain, Charsets.UTF_8))\n        root.remove("exportedAt")\n        root.remove("exportedAtMillis")\n        return GitHubSyncCrypto.sha256(root.toString().toByteArray(Charsets.UTF_8))\n    }\n\n    private fun isEmptySnapshot(plain: ByteArray): Boolean {
        val root = JSONObject(String(plain, Charsets.UTF_8))
        val prefs = root.optJSONObject("prefs") ?: JSONObject()
        return (root.optJSONArray("credits")?.length() ?: 0) == 0 &&
            (root.optJSONArray("transactions")?.length() ?: 0) == 0 &&
            (root.optJSONArray("subscriptions")?.length() ?: 0) == 0 &&
            prefs.optDouble("savings_rate", 0.0) == 0.0 &&
            prefs.optDouble("savings_goal_target", 0.0) == 0.0 &&
            prefs.optDouble("savings_monthly_contribution", 0.0) == 0.0 &&
            prefs.optDouble("monthly_essential_expenses", 0.0) == 0.0 &&
            prefs.optDouble("debt_plan_extra", 0.0) == 0.0
    }
}

class GitHubSyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        if (!GitHubSyncSettings.isConfigured(applicationContext)) return Result.success()
        val mode = inputData.getString("mode") ?: "auto"
        return try {
            GitHubSyncEngine.sync(applicationContext, mode)
            Result.success()
        } catch (error: Exception) {
            GitHubSyncSettings.setStatus(
                applicationContext,
                "Ошибка синхронизации: ${error.message ?: error.javaClass.simpleName}"
            )
            if (error is IOException && (error !is GitHubApiException || error.statusCode >= 500 || error.statusCode == 429)) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }
}

object GitHubSyncScheduler {
    private const val PERIODIC_NAME = "debtsavings-github-sync-periodic"
    private const val DEBOUNCED_NAME = "debtsavings-github-sync-debounced"

    private fun constraints() = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun ensurePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<GitHubSyncWorker>(1, TimeUnit.HOURS)
            .setConstraints(constraints())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    fun scheduleDebounced(context: Context) {
        if (!GitHubSyncSettings.isConfigured(context)) return
        val request = OneTimeWorkRequestBuilder<GitHubSyncWorker>()
            .setInitialDelay(30, TimeUnit.SECONDS)
            .setConstraints(constraints())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            DEBOUNCED_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    fun enqueueNow(context: Context, mode: String = "auto") {
        val request = OneTimeWorkRequestBuilder<GitHubSyncWorker>()
            .setInputData(Data.Builder().putString("mode", mode).build())
            .setConstraints(constraints())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            DEBOUNCED_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(DEBOUNCED_NAME)
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_NAME)
    }
}
