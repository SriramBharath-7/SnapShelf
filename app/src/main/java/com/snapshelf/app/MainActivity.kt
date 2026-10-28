package com.snapshelf.app

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.compose.*
import coil.compose.AsyncImage
import com.snapshelf.app.data.*
import kotlinx.coroutines.launch
import java.io.File

private val Ink = Color(0xFF0B0D10)
private val Panel = Color(0xFF15191F)
private val Blue = Color(0xFF73A7FF)

class MainActivity : ComponentActivity() {
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
            BiometricPrompt.PromptInfo.Builder().setTitle("Unlock SnapShelf")
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
    fun savePosition(id: String, p: Float) = viewModelScope.launch { repo.updatePosition(id, p) }
    fun reorder(folderId: String, ids: List<String>) = viewModelScope.launch { repo.reorder(folderId, ids) }
    fun protect(i: LibraryImage) = viewModelScope.launch { repo.protect(i) }
    fun decryptForViewing(i: LibraryImage) = repo.decryptForViewing(i)
    init { viewModelScope.launch { repo.reconcileStorage() } }
    suspend fun image(id: String) = repo.image(id)
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
        Header("SnapShelf", "Your quiet library for long screenshots")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = { dialog = DialogState.CreateSubject }, colors = ButtonDefaults.buttonColors(containerColor = Blue), modifier = Modifier.weight(1f)) { Text("＋ New subject", color = Ink) }
            OutlinedButton(onClick = openVault, modifier = Modifier.weight(1f)) { Text("Protected vault", color = Blue) }
        }
        TextButton(onClick = openSettings) { Text("Settings", color = Color.Gray) }
        Spacer(Modifier.height(24.dp)); Text("Subjects", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        if (subjects.isEmpty()) EmptyState("Your library is empty", "Create a subject, then add units and screenshots.")
        LazyVerticalGrid(columns = GridCells.Adaptive(155.dp), modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 14.dp)) {
            items(subjects, key = { it.id }) { subject ->
                Card(onClick = { open(subject) }, colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("▦", color = Blue, style = MaterialTheme.typography.headlineMedium)
                        Spacer(Modifier.height(18.dp)); Text(subject.name, fontWeight = FontWeight.SemiBold)
                        Row { Text("Open", color = Color.Gray, style = MaterialTheme.typography.bodySmall); Spacer(Modifier.weight(1f)); Text("⋮", modifier = Modifier.clickable { dialog = DialogState.SubjectActions(subject) }) }
                    }
                }
            }
        }
    }
    dialog?.let { state ->
        when (state) {
            DialogState.CreateSubject -> NameDialog("Create subject", "e.g. Java", { vm.createSubject(it); dialog = null }, { dialog = null })
            is DialogState.SubjectActions -> ActionDialog(state.subject.name, { dialog = DialogState.RenameSubject(state.subject) }, { dialog = DialogState.ConfirmDeleteSubject(state.subject) }, { dialog = null })
            is DialogState.RenameSubject -> NameDialog("Rename subject", state.subject.name, { vm.renameSubject(state.subject, it); dialog = null }, { dialog = null })
            is DialogState.ConfirmDeleteSubject -> ConfirmDialog("Delete subject?", "Its units and imported files will be deleted.", { vm.deleteSubject(state.subject); dialog = null }, { dialog = null })
            else -> Unit
        }
    }
}

@Composable
private fun SubjectScreen(vm: LibraryViewModel, id: String, back: () -> Unit, open: (Folder) -> Unit) {
    val folders by vm.folders(id).collectAsStateWithLifecycle(emptyList())
    var dialog by remember { mutableStateOf<DialogState?>(null) }
    Screen {
        BackHeader("Subject", back)
        Button(onClick = { dialog = DialogState.CreateFolder }, colors = ButtonDefaults.buttonColors(containerColor = Blue), modifier = Modifier.fillMaxWidth()) { Text("＋ New unit", color = Ink) }
        Spacer(Modifier.height(24.dp)); Text("Units", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        if (folders.isEmpty()) EmptyState("No units yet", "Keep screenshots grouped into focused units.")
        folders.forEach { folder ->
            Card(onClick = { open(folder) }, colors = CardDefaults.cardColors(containerColor = Panel), modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("▤", color = Blue, style = MaterialTheme.typography.headlineSmall); Spacer(Modifier.width(14.dp)); Text(folder.name, fontWeight = FontWeight.SemiBold); Spacer(Modifier.weight(1f))
                    Text("⋮", modifier = Modifier.clickable { dialog = DialogState.FolderActions(folder) })
                }
            }
        }
    }
    dialog?.let { state ->
        when (state) {
            DialogState.CreateFolder -> NameDialog("Create unit", "e.g. Unit 1", { vm.createFolder(id, it); dialog = null }, { dialog = null })
            is DialogState.FolderActions -> ActionDialog(state.folder.name, { dialog = DialogState.RenameFolder(state.folder) }, { dialog = DialogState.ConfirmDeleteFolder(state.folder) }, { dialog = null })
            is DialogState.RenameFolder -> NameDialog("Rename unit", state.folder.name, { vm.renameFolder(state.folder, it); dialog = null }, { dialog = null })
            is DialogState.ConfirmDeleteFolder -> ConfirmDialog("Delete unit?", "Its screenshots and imported files will be deleted.", { vm.deleteFolder(state.folder); dialog = null }, { dialog = null })
            else -> Unit
        }
    }
}

@Composable
private fun FolderScreen(vm: LibraryViewModel, id: String, back: () -> Unit, open: (String) -> Unit) {
    val images by vm.images(id).collectAsStateWithLifecycle(emptyList())
    var dialog by remember { mutableStateOf<DialogState?>(null) }
    val folder by produceState<Folder?>(null, id) { value = vm.folder(id) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris -> if (uris.isNotEmpty() && folder != null) vm.importImages(folder!!, uris) }
    Screen {
        BackHeader(folder?.name ?: "Unit", back)
        Row(verticalAlignment = Alignment.CenterVertically) { Text("${images.size} screenshots", color = Color.Gray); Spacer(Modifier.weight(1f)); OutlinedButton(onClick = { picker.launch("image/*") }) { Text("Import", color = Blue) } }
        if (images.isEmpty()) EmptyState("No screenshots", "Import PNG, JPEG, or WebP images in a batch.")
        LazyVerticalGrid(columns = GridCells.Adaptive(120.dp), modifier = Modifier.weight(1f), contentPadding = PaddingValues(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(images, key = { it.id }) { image ->
                Card(onClick = { open(image.id) }, colors = CardDefaults.cardColors(containerColor = Panel)) {
                    AsyncImage(model = image.path.takeIf { !image.isProtected }, contentDescription = image.displayName, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(170.dp))
                    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) { Text(image.displayName, maxLines = 1, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall); Text("⋮", modifier = Modifier.clickable { dialog = DialogState.ImageActions(image) }) }
                }
            }
        }
        ReorderBar(images, vm, id)
    }
    dialog?.let { state ->
        when (state) {
            is DialogState.ImageActions -> ActionDialog(state.image.displayName, { dialog = DialogState.RenameImage(state.image) }, { dialog = DialogState.ConfirmDeleteImage(state.image) }, { dialog = null }, extra = if (!state.image.isProtected) ({ vm.protect(state.image); dialog = null }) else null)
            is DialogState.RenameImage -> NameDialog("Rename screenshot", state.image.displayName, { vm.renameImage(state.image, it); dialog = null }, { dialog = null })
            is DialogState.ConfirmDeleteImage -> ConfirmDialog("Delete screenshot?", "The imported copy will be removed.", { vm.deleteImage(state.image); dialog = null }, { dialog = null })
            else -> Unit
        }
    }
}

@Composable
private fun ReorderBar(images: List<LibraryImage>, vm: LibraryViewModel, folderId: String) {
    var ordered by remember(images) { mutableStateOf(images) }
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.End) {
        Text("Reorder:", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
        ordered.forEachIndexed { index, image ->
            TextButton(onClick = {
                if (index > 0) {
                    val next = ordered.toMutableList().apply { add(index - 1, removeAt(index)) }
                    ordered = next; vm.reorder(folderId, next.map { it.id })
                }
            }) { Text(if (index == 0) "↑" else "${index + 1}↑", color = Blue) }
        }
    }
}

@Composable
private fun ViewerScreen(vm: LibraryViewModel, id: String, back: () -> Unit, authenticate: ((() -> Unit) -> Unit)) {
    var collection by remember { mutableStateOf<List<LibraryImage>>(emptyList()) }
    var currentIndex by remember { mutableIntStateOf(0) }
    LaunchedEffect(id) { collection = vm.collection(id); currentIndex = collection.indexOfFirst { it.id == id }.coerceAtLeast(0) }
    val image = collection.getOrNull(currentIndex)
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }; var offsetY by remember { mutableFloatStateOf(0f) }
    var tempPath by remember(image?.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(image?.id) {
        scale = 1f; offsetX = 0f; offsetY = 0f
        if (image?.isProtected == true) authenticate { tempPath = vm.decryptForViewing(image) }
    }
    DisposableEffect(tempPath) { onDispose { tempPath?.let { File(it).delete() } } }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        image?.let { current ->
            AsyncImage(model = tempPath ?: current.path.takeIf { !current.isProtected }, contentDescription = current.displayName, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().pointerInput(current.id) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        if (scale <= 1.01f && zoom == 1f && kotlin.math.abs(pan.x) > kotlin.math.abs(pan.y) && kotlin.math.abs(pan.x) > 40f) {
                            if (pan.x < 0 && currentIndex < collection.lastIndex) currentIndex++ else if (pan.x > 0 && currentIndex > 0) currentIndex--
                        } else { scale = (scale * zoom).coerceIn(1f, 5f); offsetX += pan.x; offsetY += pan.y }
                    }
                }.graphicsLayer(scaleX = scale, scaleY = scale, translationX = offsetX, translationY = offsetY))
        }
        IconButton(onClick = back, modifier = Modifier.statusBarsPadding().padding(8.dp)) { Text("‹", color = Color.White, style = MaterialTheme.typography.displaySmall) }
        if (collection.isNotEmpty()) {
            Text("${currentIndex + 1} / ${collection.size}", color = Color.White, modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(18.dp))
            Row(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { if (currentIndex > 0) currentIndex--; }, enabled = currentIndex > 0) { Text("Previous") }
                Button(onClick = { if (currentIndex < collection.lastIndex) currentIndex++; }, enabled = currentIndex < collection.lastIndex) { Text("Next") }
            }
        }
    }
}

@Composable
private fun VaultScreen(vm: LibraryViewModel, back: () -> Unit, open: (String) -> Unit) {
    val images by vm.protectedImages.collectAsStateWithLifecycle(emptyList())
    Screen { BackHeader("Protected vault", back); Text("Encrypted screenshots", color = Color.Gray); if (images.isEmpty()) EmptyState("Vault is empty", "Protect a screenshot from a unit to keep it here."); images.forEach { image -> Card(onClick = { open(image.id) }, colors = CardDefaults.cardColors(containerColor = Panel), modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) { Text(image.displayName, modifier = Modifier.padding(18.dp)) } } }
}

@Composable
private fun SettingsScreen(context: Context, back: () -> Unit) {
    val preferences = remember(context) { context.getSharedPreferences("security", Context.MODE_PRIVATE) }
    var enabled by remember { mutableStateOf(preferences.getBoolean("app_lock", false)) }
    Screen {
        BackHeader("Settings", back)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) { Text("App lock", fontWeight = FontWeight.SemiBold); Text("Require biometric or device credentials after backgrounding.", color = Color.Gray, style = MaterialTheme.typography.bodySmall) }
            Switch(checked = enabled, onCheckedChange = { enabled = it; preferences.edit().putBoolean("app_lock", it).apply() })
        }
    }
}

private sealed interface DialogState {
    data object CreateSubject : DialogState
    data object CreateFolder : DialogState
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

@Composable private fun Screen(content: @Composable ColumnScope.() -> Unit) { Column(Modifier.fillMaxSize().background(Ink).statusBarsPadding().padding(horizontal = 20.dp).padding(top = 20.dp), content = content) }
@Composable private fun Header(title: String, subtitle: String) { Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold); Text(subtitle, color = Color.Gray, modifier = Modifier.padding(top = 5.dp, bottom = 24.dp)) }
@Composable private fun BackHeader(title: String, back: () -> Unit) { Row(verticalAlignment = Alignment.CenterVertically) { Text("‹", color = Blue, style = MaterialTheme.typography.displaySmall, modifier = Modifier.clickable { back() }); Spacer(Modifier.width(10.dp)); Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }; Spacer(Modifier.height(20.dp)) }
@Composable private fun EmptyState(title: String, body: String) { Column(Modifier.fillMaxWidth().padding(vertical = 45.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text("▧", color = Blue, style = MaterialTheme.typography.displaySmall); Spacer(Modifier.height(12.dp)); Text(title, fontWeight = FontWeight.SemiBold); Text(body, color = Color.Gray, style = MaterialTheme.typography.bodySmall) } }
@Composable private fun NameDialog(title: String, hint: String, confirm: (String) -> Unit, cancel: () -> Unit) { var value by remember { mutableStateOf("") }; AlertDialog(onDismissRequest = cancel, title = { Text(title) }, text = { OutlinedTextField(value, { value = it }, placeholder = { Text(hint) }, singleLine = true) }, confirmButton = { TextButton(onClick = { if (value.isNotBlank()) confirm(value) }) { Text("Save", color = Blue) } }, dismissButton = { TextButton(onClick = cancel) { Text("Cancel") } }) }
@Composable private fun ActionDialog(title: String, rename: () -> Unit, delete: () -> Unit, cancel: () -> Unit, extra: (() -> Unit)? = null) { AlertDialog(onDismissRequest = cancel, title = { Text(title) }, text = { Column { TextButton(rename) { Text("Rename") }; extra?.let { TextButton(it) { Text("Protect with vault") } }; TextButton(delete) { Text("Delete", color = MaterialTheme.colorScheme.error) } } }, confirmButton = {}, dismissButton = { TextButton(cancel) { Text("Cancel") } }) }
@Composable private fun ConfirmDialog(title: String, body: String, confirm: () -> Unit, cancel: () -> Unit) { AlertDialog(onDismissRequest = cancel, title = { Text(title) }, text = { Text(body) }, confirmButton = { TextButton(confirm) { Text("Delete", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton(cancel) { Text("Cancel") } }) }
@Composable private fun SnapShelfTheme(content: @Composable () -> Unit) { MaterialTheme(colorScheme = darkColorScheme(primary = Blue, background = Ink, surface = Panel, onBackground = Color.White, onSurface = Color.White), content = content) }
