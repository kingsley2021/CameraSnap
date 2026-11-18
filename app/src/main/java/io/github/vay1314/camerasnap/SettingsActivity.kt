package io.github.vay1314.camerasnap

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

private data class CameraOption(val id: String, val label: String)
private data class PhotoCapabilities(
    val cameras: List<CameraOption>, val formats: List<String>,
    val sizes: List<PhotoOptions.Resolution>, val format: String, val size: String
)

class SettingsActivity : ComponentActivity() {
    private var mode by mutableIntStateOf(SnapConfig.OFF)
    private var cameraId by mutableStateOf("")
    private var photoFormat by mutableStateOf(PhotoOptions.JPEG)
    private var photoSize by mutableStateOf("")
    private var photoVibration by mutableIntStateOf(PhotoFeedback.EVERY)
    private var photoFormats by mutableStateOf(listOf(PhotoOptions.JPEG))
    private var photoSizes by mutableStateOf(emptyList<PhotoOptions.Resolution>())
    private var photoOptionsBusy by mutableStateOf(false)
    private var photoOptionsError by mutableStateOf<String?>(null)
    private var capabilityRequest = 0
    private var destination by mutableStateOf(MediaSavePath.DEFAULT)
    private var destinationBusy by mutableStateOf(false)
    private var hidden by mutableStateOf(false)
    private var language by mutableStateOf("")
    private var connection by mutableStateOf("")
    private var captureStatus by mutableStateOf("")
    private var cameras by mutableStateOf(emptyList<CameraOption>())
    private var pendingMode = SnapConfig.OFF
    private val settingsIo = Executors.newSingleThreadExecutor()
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        runOnUiThread { refreshState() }
    }
    private val directoryPicker = registerForActivityResult(object : ActivityResultContracts.OpenDocumentTree() {
        override fun createIntent(context: Context, input: Uri?): Intent =
            super.createIntent(context, input).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
    }) { uri ->
        if (uri != null) {
            destinationBusy = true
            val appContext = applicationContext
            val started = SystemClock.elapsedRealtime()
            settingsIo.execute {
                val result = runCatching {
                    val resolver = appContext.contentResolver
                    val document = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
                    val columns = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_FLAGS)
                    val name = resolver.query(document, columns, null, null, null)?.use { cursor ->
                        check(cursor.moveToFirst()) { getString(R.string.folder_missing) }
                        check(cursor.getString(1) == DocumentsContract.Document.MIME_TYPE_DIR &&
                            cursor.getInt(2) and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE != 0) { getString(R.string.folder_read_only) }
                        cursor.getString(0)
                    } ?: error(getString(R.string.folder_unreadable))
                    resolver.takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    SnapConfig.prefs(appContext).edit {
                        putString("save_tree", uri.toString())
                        putString("save_tree_name", name)
                    }
                    resolver.notifyChange(SnapConfig.URI, null)
                }
                Log.d(HookEntry.TAG, "Directory selection completed in ${SystemClock.elapsedRealtime() - started}ms")
                runOnUiThread {
                    if (!isDestroyed && !isFinishing) {
                        destinationBusy = false
                        refreshState()
                        result.fold(
                            onSuccess = { toast(getString(R.string.folder_updated)) },
                            onFailure = { error ->
                                Log.w(HookEntry.TAG, "Cannot select save directory", error)
                                toast(getString(R.string.folder_failed, AppText.error(error).render(this)))
                            }
                        )
                    }
                }
            }
        }
    }
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val requested = pendingMode
        pendingMode = SnapConfig.OFF
        if (hasPermissions(requested)) saveMode(requested)
        else {
            SnapConfig.status(this, R.string.permission_required)
            refreshState()
        }
        loadCameras()
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingMode = savedInstanceState?.getInt("pending_mode", SnapConfig.OFF) ?: SnapConfig.OFF
        enableEdgeToEdge()
        refreshState()
        setContent {
            MiuixTheme(colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) { SettingsScreen() }
        }
    }

    override fun onResume() {
        super.onResume()
        SnapConfig.prefs(this).registerOnSharedPreferenceChangeListener(preferenceListener)
        val currentMode = SnapConfig.read(this).mode
        if (currentMode != SnapConfig.OFF && !hasPermissions(currentMode)) saveMode(SnapConfig.OFF)
        refreshState()
        loadCameras()
    }

    override fun onPause() {
        SnapConfig.prefs(this).unregisterOnSharedPreferenceChangeListener(preferenceListener)
        super.onPause()
    }

    override fun onDestroy() {
        settingsIo.shutdown()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("pending_mode", pendingMode)
        super.onSaveInstanceState(outState)
    }

    @Composable
    private fun SettingsScreen() {
        Scaffold(topBar = { TopAppBar(title = getString(R.string.app_name)) }) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().imePadding(),
                contentPadding = PaddingValues(
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 24.dp,
                    start = 16.dp, end = 16.dp
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        BasicComponent(title = getString(R.string.how_to_use), summary = getString(R.string.how_to_use_summary))
                        BasicComponent(title = getString(R.string.first_use), summary = getString(R.string.first_use_summary))
                    }
                }
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        BasicComponent(title = getString(R.string.system_connection), summary = connection)
                        BasicComponent(title = getString(R.string.recent_capture), summary = captureStatus)
                    }
                }
                item {
                    SmallTitle(getString(R.string.capture_mode))
                    Card(modifier = Modifier.fillMaxWidth().selectableGroup()) {
                        Choice(getString(R.string.mode_off), getString(R.string.mode_off_summary), mode == SnapConfig.OFF) { selectMode(SnapConfig.OFF) }
                        Choice(getString(R.string.mode_photo), getString(R.string.mode_photo_summary), mode == SnapConfig.PHOTO) { selectMode(SnapConfig.PHOTO) }
                        Choice(getString(R.string.mode_video), getString(R.string.mode_video_summary), mode == SnapConfig.VIDEO) { selectMode(SnapConfig.VIDEO) }
                    }
                }
                item {
                    SmallTitle(getString(R.string.capture_settings))
                    Card(modifier = Modifier.fillMaxWidth()) {
                        WindowDropdownPreference(
                            title = getString(R.string.camera),
                            items = cameras.map { it.label },
                            selectedIndex = cameras.indexOfFirst { it.id == cameraId },
                            summary = if (cameras.any { it.id == cameraId }) null else
                                getString(R.string.camera_unavailable, cameraId),
                            enabled = cameras.isNotEmpty(),
                            onSelectedIndexChange = { index ->
                                val option = cameras[index]
                                SnapConfig.prefs(this@SettingsActivity).edit {
                                    putString("camera_id", option.id)
                                    if (option.id != cameraId) putString("photo_size", "")
                                }
                                notifyConfigChanged()
                                refreshState()
                                loadCameras()
                            }
                        )
                        WindowDropdownPreference(
                            title = getString(R.string.photo_format),
                            summary = if (photoOptionsBusy) getString(R.string.loading_camera) else photoOptionsError ?:
                                if (PhotoOptions.HEIC !in photoFormats) getString(R.string.jpeg_only) else null,
                            entry = DropdownEntry(photoFormats.map { format ->
                                DropdownItem(
                                    text = if (format == PhotoOptions.HEIC) getString(R.string.heic_format) else "JPG",
                                    summary = if (format == PhotoOptions.HEIC) getString(R.string.heic_hint) else null,
                                    selected = format == photoFormat,
                                    onClick = {
                                        SnapConfig.prefs(this@SettingsActivity).edit {
                                            putString("photo_format", format)
                                            if (format != photoFormat) putString("photo_size", "")
                                        }
                                        notifyConfigChanged()
                                        refreshState()
                                        loadCameras()
                                    }
                                )
                            }),
                            enabled = !photoOptionsBusy && photoOptionsError == null
                        )
                        val locale = resources.configuration.locales[0]
                        val automatic = remember(photoSizes, locale) {
                            runCatching { PhotoOptions.choose(photoSizes, "").label(locale) }.getOrNull()
                        }
                        WindowDropdownPreference(
                            title = getString(R.string.photo_resolution),
                            summary = if (photoOptionsBusy) getString(R.string.loading_camera) else photoOptionsError ?:
                                if (photoSize.isEmpty()) automatic ?: getString(R.string.auto_size_hint) else null,
                            entry = DropdownEntry(
                                listOf(DropdownItem(
                                    text = getString(R.string.automatic),
                                    summary = automatic ?: getString(R.string.auto_size_hint),
                                    selected = photoSize.isEmpty(),
                                    onClick = { savePhotoSize("") }
                                )) + photoSizes.map { size ->
                                    DropdownItem(
                                        text = size.label(locale),
                                        summary = if (size.pixels() > 20_000_000) getString(R.string.high_resolution_hint) else null,
                                        selected = photoSize == size.key(),
                                        onClick = { savePhotoSize(size.key()) }
                                    )
                                }
                            ),
                            maxHeight = 420.dp,
                            enabled = !photoOptionsBusy && photoOptionsError == null && photoSizes.isNotEmpty()
                        )
                        val vibrationOptions = listOf(
                            Triple(PhotoFeedback.EVERY, R.string.vibration_every, R.string.vibration_every_hint),
                            Triple(PhotoFeedback.FIRST, R.string.vibration_first, R.string.vibration_first_hint),
                            Triple(PhotoFeedback.OFF, R.string.vibration_off, R.string.vibration_off_hint)
                        )
                        WindowDropdownPreference(
                            title = getString(R.string.photo_vibration),
                            entry = DropdownEntry(vibrationOptions.map { (value, label, hint) ->
                                DropdownItem(
                                    text = getString(label), summary = getString(hint),
                                    selected = photoVibration == value,
                                    onClick = {
                                        SnapConfig.prefs(this@SettingsActivity).edit { putInt("photo_vibration", value) }
                                        notifyConfigChanged()
                                        refreshState()
                                    }
                                )
                            })
                        )
                        BasicComponent(
                            title = getString(R.string.save_folder), summary = destination,
                            onClick = ::chooseDestination,
                            endActions = { Text(if (destinationBusy) getString(R.string.saving) else getString(R.string.change), color = MiuixTheme.colorScheme.primary) }
                        )
                    }
                }
                item {
                    SmallTitle(getString(R.string.app_settings))
                    Card(modifier = Modifier.fillMaxWidth()) {
                        SwitchPreference(
                            title = getString(R.string.hide_icon), checked = hidden,
                            summary = getString(R.string.hide_icon_summary),
                            onCheckedChange = ::setIconHidden
                        )
                        val languages = listOf("", "zh-CN", "zh-TW", "en", "de", "ru")
                        WindowDropdownPreference(
                            title = getString(R.string.language),
                            items = listOf(
                                getString(R.string.language_system),
                                getString(R.string.language_zh_cn), getString(R.string.language_zh_tw),
                                getString(R.string.language_en), getString(R.string.language_de),
                                getString(R.string.language_ru)
                            ),
                            selectedIndex = languages.indexOf(language).coerceAtLeast(0),
                            onSelectedIndexChange = { index -> selectLanguage(languages[index]) }
                        )
                    }
                }
                item {
                    SmallTitle(getString(R.string.other_settings))
                    Card(modifier = Modifier.fillMaxWidth()) {
                        ArrowPreference(
                            title = getString(R.string.project_address),
                            onClick = ::openProject
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun Choice(title: String, summary: String?, checked: Boolean, onClick: () -> Unit) {
        BasicComponent(
            title = title, summary = summary, onClick = onClick,
            modifier = Modifier.semantics { role = Role.RadioButton; selected = checked },
            endActions = { RadioButton(selected = checked, onClick = null) }
        )
    }

    private fun hasPermissions(value: Int) =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
            (value != SnapConfig.VIDEO || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)

    private fun selectMode(value: Int) {
        if (value == SnapConfig.OFF || hasPermissions(value)) { saveMode(value); return }
        pendingMode = value
        val requested = mutableListOf(Manifest.permission.CAMERA)
        if (value == SnapConfig.VIDEO) requested += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33) requested += Manifest.permission.POST_NOTIFICATIONS
        permissions.launch(requested.toTypedArray())
    }

    private fun saveMode(value: Int) {
        requestStop()
        SnapConfig.prefs(this).edit { putInt("mode", value) }
        notifyConfigChanged()
        refreshState()
    }

    private fun requestStop() {
        try { startService(Intent(this, SnapService::class.java).setAction(SnapConfig.ACTION_STOP)) }
        catch (e: RuntimeException) { Log.w(HookEntry.TAG, "Cannot stop capture", e); toast(getString(R.string.stop_failed)) }
    }

    private fun notifyConfigChanged() = contentResolver.notifyChange(SnapConfig.URI, null)

    private fun openProject() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, "https://github.com/vay1314/CameraSnap".toUri()))
        } catch (e: RuntimeException) {
            Log.w(HookEntry.TAG, "Cannot open project homepage", e)
            toast(getString(R.string.project_open_failed))
        }
    }

    private fun savePhotoSize(value: String) {
        SnapConfig.prefs(this).edit { putString("photo_size", value) }
        notifyConfigChanged()
        refreshState()
    }

    private fun chooseDestination() {
        if (destinationBusy) return
        try {
            val tree = SnapConfig.read(this).saveTree
            directoryPicker.launch(if (tree.isEmpty()) null else Uri.parse(tree))
        } catch (e: RuntimeException) {
            Log.w(HookEntry.TAG, "Cannot open directory picker", e)
            toast(getString(R.string.picker_failed))
        }
    }

    private fun launcher() = ComponentName(this, SnapConfig.PACKAGE + ".LauncherActivity")

    private fun selectLanguage(tag: String) {
        try { AppLanguage.select(this, tag) }
        catch (e: RuntimeException) {
            Log.w(HookEntry.TAG, "Cannot change app language", e)
            toast(getString(R.string.language_change_failed))
        }
    }

    private fun setIconHidden(value: Boolean) {
        try {
            packageManager.setComponentEnabledSetting(launcher(),
                if (value) PackageManager.COMPONENT_ENABLED_STATE_DISABLED else PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP)
            toast(if (value) getString(R.string.icon_hidden) else getString(R.string.icon_restored))
        } catch (e: RuntimeException) { Log.w(HookEntry.TAG, "Cannot change launcher visibility", e); toast(getString(R.string.icon_failed)) }
        refreshState()
    }

    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_SHORT).show()

    private fun refreshState() {
        language = AppLanguage.get(this)
        val config = SnapConfig.read(this)
        mode = config.mode
        cameraId = config.cameraId
        photoFormat = config.photoFormat
        photoSize = config.photoSize
        photoVibration = config.photoVibration
        destination = config.destinationText().render(this)
        hidden = packageManager.getComponentEnabledSetting(launcher()) in setOf(
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED)
        val prefs = SnapConfig.prefs(this)
        val time = prefs.getLong("system_ready_at", 0)
        connection = if (time == 0L) getString(R.string.connection_waiting) else getString(R.string.connection_last,
            DateFormat.getDateTimeInstance(DateFormat.DEFAULT, DateFormat.DEFAULT, resources.configuration.locales[0]).format(Date(time)))
        captureStatus = SnapConfig.statusText(this)
    }

    private fun loadCameras() {
        val appContext = applicationContext
        val config = SnapConfig.read(this)
        val request = ++capabilityRequest
        photoOptionsBusy = true
        photoOptionsError = null
        settingsIo.execute {
            val options = mutableListOf(CameraOption("", getString(R.string.camera_auto)))
            val result = runCatching {
                val manager = appContext.getSystemService(CameraManager::class.java)
                manager.cameraIdList.forEach { id ->
                    val facing = when (manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)) {
                        CameraCharacteristics.LENS_FACING_BACK -> getString(R.string.camera_back)
                        CameraCharacteristics.LENS_FACING_FRONT -> getString(R.string.camera_front)
                        else -> getString(R.string.camera_external)
                    }
                    options += CameraOption(id, "$facing · $id")
                }
                val id = PhotoOptions.cameraId(manager, config.cameraId)
                val map = manager.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val jpeg = PhotoOptions.supported(map, PhotoOptions.JPEG)
                val heic = PhotoOptions.supported(map, PhotoOptions.HEIC)
                check(jpeg.isNotEmpty()) { getString(R.string.no_jpeg) }
                val formats = if (heic.isEmpty()) listOf(PhotoOptions.JPEG) else listOf(PhotoOptions.JPEG, PhotoOptions.HEIC)
                val format = if (config.photoFormat in formats) config.photoFormat else PhotoOptions.JPEG
                val sizes = if (format == PhotoOptions.HEIC) heic else jpeg
                val size = config.photoSize.takeIf { key -> sizes.any { it.key() == key } } ?: ""
                PhotoCapabilities(options, formats, sizes, format, size)
            }
            runOnUiThread {
                if (!isDestroyed && !isFinishing && request == capabilityRequest) {
                    photoOptionsBusy = false
                    result.fold(onSuccess = { loaded ->
                        cameras = loaded.cameras
                        photoFormats = loaded.formats
                        photoSizes = loaded.sizes
                        if (loaded.format != config.photoFormat || loaded.size != config.photoSize) {
                            SnapConfig.prefs(this).edit {
                                putString("photo_format", loaded.format)
                                putString("photo_size", loaded.size)
                            }
                            notifyConfigChanged()
                            refreshState()
                        }
                    }, onFailure = { error ->
                        cameras = options
                        photoSizes = emptyList()
                        photoOptionsError = getString(R.string.camera_read_failed, AppText.error(error).render(this))
                        Log.w(HookEntry.TAG, "Cannot enumerate camera photo capabilities", error)
                    })
                }
            }
        }
    }
}
