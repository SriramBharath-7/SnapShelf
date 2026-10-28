package com.snapshelf.app

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.compose.*
import coil.compose.AsyncImage
import com.snapshelf.app.data.*
import kotlinx.coroutines.launch
import java.io.File

private val Black = Color(0xFF000000)
private val Navy = Color(0xFF0D1724)
private val Cyan = Color(0xFF38E8FF)
private val White = Color(0xFFFFFFFF)
private val Steel = Color(0xFF91A7BF)
private val Slate = Color(0xFF24394B)
private val ErrorRed = Color(0xFFFF7185)
private val CardShape = RoundedCornerShape(22.dp)

class MainActivity : FragmentActivity() {
    private var returningFromBackground = false
    private val vm by viewModels<LibraryViewModel> {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val db = LibraryDatabase.create(applicationContext)
                @Suppress("UNCHECKED_CAST")
                return LibraryViewModel(applicationContext, LibraryRepository(applicationContext, db.dao())) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SnapShelfTheme { SnapShelfApp(vm, ::authenticate) } }
    }

    override fun onStop() {
        super.onStop()
        returningFromBackground = true
    }

    override fun onStart() {
        super.onStart()
        if (returningFromBackground && getSharedPreferences("security", MODE_PRIVATE).getBoolean("app_lock", false)) {
            authenticate { returningFromBackground = false }
        }
    }

    private fun authenticate(onSuccess: () -> Unit) {
        val executor = ContextCompat.getMainExecutor(this)
        BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
        }).authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock SnapShelf")
                .setSubtitle("Authenticate to access protected screenshots")
                .setAllowedAuthenticators(
                    androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
                ).build()
        )
    }
}

class LibraryViewModel(val context: Context, private val repo: LibraryRepository) : ViewModel() {
    val subjects = repo.subjects
    val protectedImages = repo.protectedImages
    fun folders(id: String) = repo.folders(id)
    fun images(id: String) = repo.images(id)
    fun createSubject(name: String) = viewModelScope.launch { repo.createSubject(name) }
    fun createFolder(id: String, name: String) = viewModelScope.launch { repo.createFolder(id, name) }
    fun renameSubject(s: Subject, name: String) = viewModelScope.launch { repo.renameSubject(s, name) }
    fun renameFolder(f: Folder, name: String) = viewModelScope.launch { repo.renameFolder(f, name) }
    fun renameImage(i: LibraryImage, name: String) = viewModelScope.launch { repo.renameImage(i, name) }
    fun deleteSubject(s: Subject) = viewModelScope.launch { repo.deleteSubject(s) }
    fun deleteFolder(f: Folder) = viewModelScope.launch { repo.deleteFolder(f) }
    fun deleteImage(i: LibraryImage) = viewModelScope.launch { repo.deleteImage(i) }
    fun importImages(f: Folder, uris: List<Uri>) = viewModelScope.launch { repo.importImages(f, uris) }
    fun reorder(folderId: String, ids: List<String>) = viewModelScope.launch { repo.reorder(folderId, ids) }
    fun protect(i: LibraryImage) = viewModelScope.launch { repo.protect(i) }
    fun decryptForViewing(i: LibraryImage) = repo.decryptForViewing(i)
    init { viewModelScope.launch { repo.reconcileStorage() } }
    suspend fun collection(id: String) = repo.collectionFor(id)
    suspend fun folder(id: String) = repo.folder(id)
}

@Composable
fun SnapShelfApp(vm: LibraryViewModel, authenticate: (() -> Unit) -> Unit) {
    val nav = rememberNavController()
    NavHost(nav, startDestination = "home", modifier = Modifier.fillMaxSize()) {
        composable("home") { HomeScreen(vm, { nav.navigate("subject/${it.id}") }, { authenticate { nav.navigate("vault") } }, { nav.navigate("settings") }) }
        composable("subject/{id}") { entry ->
            val id = entry.arguments?.getString("id") ?: return@composable
            SubjectScreen(vm, id, { nav.popBackStack() }, { nav.navigate("folder/${it.id}") })
        }
        composable("folder/{id}") { entry ->
            val id = entry.arguments?.getString("id") ?: return@composable
            FolderScreen(vm, id, { nav.popBackStack() }, { nav.navigate("viewer/$it") })
        }
        composable("viewer/{id}") { entry ->
            val id = entry.arguments?.getString("id") ?: return@composable
            ViewerScreen(vm, id, { nav.popBackStack() }, authenticate)
        }
        composable("vault") { VaultScreen(vm, { nav.popBackStack() }) { nav.navigate("viewer/$it") } }
        composable("settings") { SettingsScreen(vm.context, { nav.popBackStack() }) }
    }
}

@Composable
private fun HomeScreen(vm: LibraryViewModel, open: (Subject) -> Unit, openVault: () -> Unit, openSettings: () -> Unit) {
    val subjects by vm.subjects.collectAsStateWithLifecycle(emptyList())
    var dialog by remember { mutableStateOf<DialogState?>(null) }
    Screen {
        TopBar("SnapShelf", "Your personal screenshot library", openSettings)
        AnimatedVisibility(visible = true, enter = fadeIn(tween(420)) + slideInVertically(tween(420), initialOffsetY = { it / 5 })) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    ActionTile("＋", "New subject", "Organize a new topic", Cyan, Modifier.weight(1f)) { dialog = DialogState.CreateSubject }
                    ActionTile("▣", "Protected", "Private vault", White, Modifier.weight(1f)) { openVault() }
                }
                LibrarySummary(subjects.size)
            }
        }
        Spacer(Modifier.height(28.dp))
        SectionHeader("Your subjects", "${subjects.size} ${if (subjects.size == 1) "subject" else "subjects"}")
        if (subjects.isEmpty()) EmptyState("Start your library", "Create a subject for a course, project, or reference set.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.weight(1f)) {
            items(subjects, key = { it.id }) { subject -> SubjectCard(subject, open) { dialog = DialogState.SubjectActions(subject) } }
        }
    }
    DialogHost(dialog, vm, { dialog = it })
}

@Composable
private fun SubjectScreen(vm: LibraryViewModel, id: String, back: () -> Unit, open: (Folder) -> Unit) {
    val folders by vm.folders(id).collectAsStateWithLifecycle(emptyList())
    var dialog by remember { mutableStateOf<DialogState?>(null) }
    Screen {
        BackHeader("Subject", back, "Units and folders")
        PrimaryButton("＋  New unit") { dialog = DialogState.CreateFolder(id) }
        Spacer(Modifier.height(28.dp))
        SectionHeader("Units", "${folders.size} ${if (folders.size == 1) "unit" else "units"}")
        if (folders.isEmpty()) EmptyState("No units yet", "Keep screenshots grouped into focused units.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.weight(1f)) {
            items(folders, key = { it.id }) { folder -> FolderCard(folder, open) { dialog = DialogState.FolderActions(folder) } }
        }
    }
    DialogHost(dialog, vm, { dialog = it })
}

@Composable
private fun FolderScreen(vm: LibraryViewModel, id: String, back: () -> Unit, open: (String) -> Unit) {
    val images by vm.images(id).collectAsStateWithLifecycle(emptyList())
    val folder by produceState<Folder?>(null, id) { value = vm.folder(id) }
    var dialog by remember { mutableStateOf<DialogState?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty() && folder != null) vm.importImages(folder!!, uris)
    }
    Screen {
        BackHeader(folder?.name ?: "Unit", back, "${images.size} screenshots")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            PrimaryButton("＋  Import screenshots", Modifier.weight(1f)) { picker.launch("image/*") }
            if (images.size > 1) SecondaryButton("↑  Order") { vm.reorder(id, images.map { it.id }.reversed()) }
        }
        Spacer(Modifier.height(22.dp))
        if (images.isEmpty()) {
            EmptyState("No screenshots yet", "Import a batch of PNG, JPEG, or WebP images.")
        } else {
            SectionHeader("Collection", "${images.size} images")
            LazyVerticalGrid(
                columns = GridCells.Adaptive(142.dp),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(images, key = { it.id }) { image ->
                    ScreenshotCard(image, open) { dialog = DialogState.ImageActions(image) }
                }
            }
        }
    }
    DialogHost(dialog, vm, { dialog = it })
}

@Composable
private fun ViewerScreen(vm: LibraryViewModel, id: String, back: () -> Unit, authenticate: ((() -> Unit) -> Unit)) {
    var collection by remember { mutableStateOf<List<LibraryImage>>(emptyList()) }
    var currentIndex by remember { mutableIntStateOf(0) }
    LaunchedEffect(id) {
        collection = vm.collection(id)
        currentIndex = collection.indexOfFirst { it.id == id }.coerceAtLeast(0)
    }
    val image = collection.getOrNull(currentIndex)
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var tempPath by remember(image?.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(image?.id) {
        scale = 1f
        offsetX = 0f
        offsetY = 0f
        if (image?.isProtected == true) authenticate { tempPath = vm.decryptForViewing(image) }
    }
    DisposableEffect(tempPath) { onDispose { tempPath?.let { File(it).delete() } } }
    Box(Modifier.fillMaxSize().background(Black)) {
        image?.let { current ->
            AsyncImage(
                model = tempPath ?: current.path.takeIf { !current.isProtected },
                contentDescription = current.displayName,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().pointerInput(current.id) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        if (scale <= 1.01f && zoom == 1f && kotlin.math.abs(pan.x) > kotlin.math.abs(pan.y) && kotlin.math.abs(pan.x) > 40f) {
                            if (pan.x < 0 && currentIndex < collection.lastIndex) currentIndex++ else if (pan.x > 0 && currentIndex > 0) currentIndex--
                        } else {
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            offsetX += pan.x
                            offsetY += pan.y
                        }
                    }
                }.graphicsLayer(scaleX = scale, scaleY = scale, translationX = offsetX, translationY = offsetY)
            )
        }
        ViewerTopBar(back, image?.displayName ?: "Screenshot", if (collection.isNotEmpty()) "${currentIndex + 1} / ${collection.size}" else "")
        if (collection.isNotEmpty()) {
            Row(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ViewerButton("‹", currentIndex > 0) { currentIndex-- }
                ViewerButton("›", currentIndex < collection.lastIndex) { currentIndex++ }
            }
        }
    }
}

@Composable
private fun VaultScreen(vm: LibraryViewModel, back: () -> Unit, open: (String) -> Unit) {
    val images by vm.protectedImages.collectAsStateWithLifecycle(emptyList())
    Screen {
        BackHeader("Protected vault", back, "Encrypted and private")
        VaultBanner()
        Spacer(Modifier.height(24.dp))
        if (images.isEmpty()) EmptyState("Vault is empty", "Protect a screenshot from a unit to keep it here.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.weight(1f)) {
            items(images, key = { it.id }) { image ->
                Surface(onClick = { open(image.id) }, color = Navy, shape = CardShape, border = BorderStroke(1.dp, Slate), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("▣", color = Cyan, style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.width(14.dp)); Text(image.displayName, color = White, modifier = Modifier.weight(1f))
                        Text("›", color = Steel, style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(context: Context, back: () -> Unit) {
    val preferences = remember(context) { context.getSharedPreferences("security", Context.MODE_PRIVATE) }
    var enabled by remember { mutableStateOf(preferences.getBoolean("app_lock", false)) }
    Screen {
        BackHeader("Settings", back, "Make SnapShelf yours")
        SettingsRow("App lock", "Require authentication after backgrounding", enabled) {
            enabled = it
            preferences.edit().putBoolean("app_lock", it).apply()
        }
        Spacer(Modifier.height(12.dp))
        Surface(color = Navy, shape = CardShape, border = BorderStroke(1.dp, Slate), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp)) {
                Text("About SnapShelf", color = White, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text("An offline-first home for the screenshots you want to keep close.", color = Steel, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun SubjectCard(subject: Subject, open: (Subject) -> Unit, actions: () -> Unit) {
    Surface(onClick = { open(subject) }, color = Navy, shape = CardShape, border = BorderStroke(1.dp, Slate), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            AccentMark("S")
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) { Text(subject.name, color = White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold); Text("Subject library", color = Steel, style = MaterialTheme.typography.bodySmall) }
            MoreButton(actions)
        }
    }
}

@Composable
private fun FolderCard(folder: Folder, open: (Folder) -> Unit, actions: () -> Unit) {
    Surface(onClick = { open(folder) }, color = Navy, shape = CardShape, border = BorderStroke(1.dp, Slate), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            AccentMark("U")
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) { Text(folder.name, color = White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold); Text("Open unit", color = Steel, style = MaterialTheme.typography.bodySmall) }
            MoreButton(actions)
        }
    }
}

@Composable
private fun ScreenshotCard(image: LibraryImage, open: (String) -> Unit, actions: () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(360), label = "cardAlpha")
    Surface(onClick = { open(image.id) }, color = Navy, shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, Slate), modifier = Modifier.graphicsLayer(alpha = alpha)) {
        Column {
            Box(Modifier.fillMaxWidth().height(170.dp).background(Color(0xFF101D2A))) {
                AsyncImage(model = image.path.takeIf { !image.isProtected }, contentDescription = image.displayName, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                if (image.isProtected) Text("▣", color = Cyan, modifier = Modifier.align(Alignment.Center), style = MaterialTheme.typography.headlineMedium)
                if (image.isProtected) Text("PROTECTED", color = Cyan, style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.TopStart).padding(10.dp))
            }
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(image.displayName, color = White, maxLines = 1, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                MoreButton(actions)
            }
        }
    }
}

@Composable private fun LibrarySummary(subjectCount: Int) {
    Surface(color = Navy, shape = CardShape, border = BorderStroke(1.dp, Slate), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("◈", color = Cyan, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.width(14.dp))
            Column { Text("Your library", color = White, fontWeight = FontWeight.Bold); Text("$subjectCount organized ${if (subjectCount == 1) "subject" else "subjects"}", color = Steel, style = MaterialTheme.typography.bodySmall) }
            Spacer(Modifier.weight(1f)); Text("OFFLINE", color = Cyan, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable private fun VaultBanner() {
    Surface(color = Navy, shape = CardShape, border = BorderStroke(1.dp, Slate), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("▣", color = Cyan, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.width(14.dp)); Column { Text("Private by design", color = White, fontWeight = FontWeight.Bold); Text("Protected files stay encrypted on this device.", color = Steel, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable private fun SettingsRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Surface(color = Navy, shape = CardShape, border = BorderStroke(1.dp, Slate), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(title, color = White, fontWeight = FontWeight.Bold); Text(subtitle, color = Steel, style = MaterialTheme.typography.bodySmall) }
            Switch(checked = checked, onCheckedChange = onCheckedChange, colors = SwitchDefaults.colors(checkedThumbColor = Black, checkedTrackColor = Cyan, uncheckedThumbColor = Steel, uncheckedTrackColor = Slate))
        }
    }
}

@Composable private fun TopBar(title: String, subtitle: String, settings: () -> Unit) {
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth().padding(bottom = 28.dp)) {
        Column(Modifier.weight(1f)) { Text(title, color = White, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black); Text(subtitle, color = Steel, modifier = Modifier.padding(top = 5.dp), style = MaterialTheme.typography.bodyMedium) }
        Text("⚙", color = Steel, style = MaterialTheme.typography.titleLarge, modifier = Modifier.clickable { settings() }.padding(4.dp))
    }
}

@Composable private fun BackHeader(title: String, back: () -> Unit, subtitle: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 26.dp)) {
        Text("‹", color = Cyan, style = MaterialTheme.typography.displaySmall, modifier = Modifier.clickable { back() })
        Spacer(Modifier.width(12.dp)); Column { Text(title, color = White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black); Text(subtitle, color = Steel, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable private fun ViewerTopBar(back: () -> Unit, title: String, counter: String) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("‹", color = White, style = MaterialTheme.typography.displaySmall, modifier = Modifier.clickable { back() })
        Spacer(Modifier.width(12.dp)); Text(title, color = White, maxLines = 1, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        Text(counter, color = Cyan, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable private fun ViewerButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Black, disabledContainerColor = Slate, disabledContentColor = Steel), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)) {
        Text(label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable private fun ActionTile(icon: String, title: String, subtitle: String, accent: Color, modifier: Modifier, onClick: () -> Unit) {
    val tint by animateColorAsState(accent, tween(250), label = "tileTint")
    Surface(onClick = onClick, color = Navy, shape = CardShape, border = BorderStroke(1.dp, Slate), modifier = modifier) {
        Column(Modifier.padding(18.dp)) { Text(icon, color = tint, style = MaterialTheme.typography.headlineSmall); Spacer(Modifier.height(18.dp)); Text(title, color = White, fontWeight = FontWeight.Bold); Text(subtitle, color = Steel, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 3.dp)) }
    }
}

@Composable private fun PrimaryButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Black), contentPadding = PaddingValues(vertical = 14.dp)) { Text(label, fontWeight = FontWeight.Bold) }
}

@Composable private fun SecondaryButton(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Slate), colors = ButtonDefaults.outlinedButtonColors(contentColor = Steel), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp)) { Text(label, fontWeight = FontWeight.Bold) }
}

@Composable private fun SectionHeader(title: String, detail: String) {
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp)) { Text(title, color = White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black); Spacer(Modifier.weight(1f)); Text(detail, color = Steel, style = MaterialTheme.typography.labelMedium) }
}

@Composable private fun AccentMark(label: String) {
    Box(Modifier.size(46.dp).background(Cyan, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) { Text(label, color = Black, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleLarge) }
}

@Composable private fun MoreButton(onClick: () -> Unit) { Text("•••", color = Steel, modifier = Modifier.clickable { onClick() }.padding(6.dp), style = MaterialTheme.typography.labelLarge) }

@Composable private fun EmptyState(title: String, body: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 54.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(64.dp).background(Navy, RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) { Text("＋", color = Cyan, style = MaterialTheme.typography.headlineMedium) }
        Spacer(Modifier.height(16.dp)); Text(title, color = White, fontWeight = FontWeight.Bold); Text(body, color = Steel, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable private fun Screen(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(Black).statusBarsPadding().padding(horizontal = 20.dp).padding(top = 20.dp), content = content)
}

private sealed interface DialogState {
    data object CreateSubject : DialogState
    data class CreateFolder(val subjectId: String) : DialogState
    data class SubjectActions(val subject: Subject) : DialogState
    data class RenameSubject(val subject: Subject) : DialogState
    data class ConfirmDeleteSubject(val subject: Subject) : DialogState
    data class FolderActions(val folder: Folder) : DialogState
    data class RenameFolder(val folder: Folder) : DialogState
    data class ConfirmDeleteFolder(val folder: Folder) : DialogState
    data class ImageActions(val image: LibraryImage) : DialogState
    data class RenameImage(val image: LibraryImage) : DialogState
    data class ConfirmDeleteImage(val image: LibraryImage) : DialogState
}

@Composable
private fun DialogHost(state: DialogState?, vm: LibraryViewModel, setState: (DialogState?) -> Unit) {
    when (state) {
        DialogState.CreateSubject -> NameDialog("New subject", "e.g. Java", { vm.createSubject(it); setState(null) }) { setState(null) }
        is DialogState.CreateFolder -> NameDialog("New unit", "e.g. Unit 1", { vm.createFolder(state.subjectId, it); setState(null) }) { setState(null) }
        is DialogState.SubjectActions -> ActionDialog(state.subject.name, { setState(DialogState.RenameSubject(state.subject)) }, { setState(DialogState.ConfirmDeleteSubject(state.subject)) }, { setState(null) })
        is DialogState.RenameSubject -> NameDialog("Rename subject", state.subject.name, { vm.renameSubject(state.subject, it); setState(null) }) { setState(null) }
        is DialogState.ConfirmDeleteSubject -> ConfirmDialog("Delete subject?", "Its units and imported files will be deleted.", { vm.deleteSubject(state.subject); setState(null) }) { setState(null) }
        is DialogState.FolderActions -> ActionDialog(state.folder.name, { setState(DialogState.RenameFolder(state.folder)) }, { setState(DialogState.ConfirmDeleteFolder(state.folder)) }, { setState(null) })
        is DialogState.RenameFolder -> NameDialog("Rename unit", state.folder.name, { vm.renameFolder(state.folder, it); setState(null) }) { setState(null) }
        is DialogState.ConfirmDeleteFolder -> ConfirmDialog("Delete unit?", "Its screenshots and imported files will be deleted.", { vm.deleteFolder(state.folder); setState(null) }) { setState(null) }
        is DialogState.ImageActions -> ActionDialog(state.image.displayName, { setState(DialogState.RenameImage(state.image)) }, { setState(DialogState.ConfirmDeleteImage(state.image)) }, { setState(null) }, if (!state.image.isProtected) ({ vm.protect(state.image); setState(null) }) else null)
        is DialogState.RenameImage -> NameDialog("Rename screenshot", state.image.displayName, { vm.renameImage(state.image, it); setState(null) }) { setState(null) }
        is DialogState.ConfirmDeleteImage -> ConfirmDialog("Delete screenshot?", "The imported copy will be removed.", { vm.deleteImage(state.image); setState(null) }) { setState(null) }
        null -> Unit
    }
}

@Composable private fun NameDialog(title: String, hint: String, confirm: (String) -> Unit, cancel: () -> Unit) { var value by remember { mutableStateOf("") }; AlertDialog(onDismissRequest = cancel, containerColor = Navy, titleContentColor = White, textContentColor = Steel, title = { Text(title) }, text = { OutlinedTextField(value, { value = it }, placeholder = { Text(hint) }, singleLine = true, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Cyan, unfocusedBorderColor = Slate, focusedTextColor = White, unfocusedTextColor = White)) }, confirmButton = { TextButton(onClick = { if (value.isNotBlank()) confirm(value) }) { Text("Save", color = Cyan) } }, dismissButton = { TextButton(onClick = cancel) { Text("Cancel", color = Steel) } }) }
@Composable private fun ActionDialog(title: String, rename: () -> Unit, delete: () -> Unit, cancel: () -> Unit, extra: (() -> Unit)? = null) { AlertDialog(onDismissRequest = cancel, containerColor = Navy, titleContentColor = White, title = { Text(title) }, text = { Column { TextButton(onClick = rename) { Text("Rename", color = White) }; extra?.let { TextButton(onClick = it) { Text("Protect with vault", color = Cyan) } }; TextButton(onClick = delete) { Text("Delete", color = ErrorRed) } } }, confirmButton = {}, dismissButton = { TextButton(onClick = cancel) { Text("Cancel", color = Steel) } }) }
@Composable private fun ConfirmDialog(title: String, body: String, confirm: () -> Unit, cancel: () -> Unit) { AlertDialog(onDismissRequest = cancel, containerColor = Navy, titleContentColor = White, textContentColor = Steel, title = { Text(title) }, text = { Text(body) }, confirmButton = { TextButton(confirm) { Text("Delete", color = ErrorRed) } }, dismissButton = { TextButton(cancel) { Text("Cancel", color = Steel) } }) }
@Composable private fun SnapShelfTheme(content: @Composable () -> Unit) { MaterialTheme(colorScheme = darkColorScheme(primary = Cyan, background = Black, surface = Navy, onBackground = White, onSurface = White), typography = Typography(), content = content) }
