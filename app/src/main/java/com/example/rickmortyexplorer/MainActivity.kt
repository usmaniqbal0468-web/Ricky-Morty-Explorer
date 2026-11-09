package com.example.rickmortyexplorer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.io.IOException

// ───────────────────────── MODELS ─────────────────────────

@Serializable
data class Info(val count: Int, val pages: Int)

@Serializable
data class ApiResponse<T>(val info: Info, val results: List<T>)

@Serializable
data class CharacterDto(
    val id: Int,
    val name: String,
    val status: String,
    val species: String,
    val image: String
)

@Serializable
data class EpisodeDto(
    val id: Int,
    val name: String,
    @SerialName("air_date") val airDate: String,
    val episode: String,
    val characters: List<String>
)

@Serializable
data class LocationDto(
    val id: Int,
    val name: String,
    val type: String,
    val dimension: String,
    val residents: List<String>
)

/** One generic model the UI can display, whatever the category is. */
data class DisplayItem(
    val name: String,
    val imageUrl: String?,
    val label1: String,
    val value1: String,
    val label2: String,
    val value2: String
)

// Episodes and locations have no image, so borrow a character's avatar
private fun avatarFromUrl(characterUrl: String?): String? =
    characterUrl?.substringAfterLast('/')?.toIntOrNull()
        ?.let { "https://rickandmortyapi.com/api/character/avatar/$it.jpeg" }

fun CharacterDto.toDisplay() =
    DisplayItem(name, image, "Status", status, "Species", species)

fun EpisodeDto.toDisplay() =
    DisplayItem(name, avatarFromUrl(characters.randomOrNull()), "Episode", episode, "Air date", airDate)

fun LocationDto.toDisplay() =
    DisplayItem(
        name, avatarFromUrl(residents.randomOrNull()),
        "Type", type.ifBlank { "unknown" },
        "Dimension", dimension.ifBlank { "unknown" }
    )

// ───────────────────────── NETWORK ─────────────────────────

private const val BASE_URL = "https://rickandmortyapi.com/api/"

interface RickAndMortyApi {
    // A null "name" is omitted from the URL by Retrofit
    @GET("character")
    suspend fun getCharacters(@Query("name") name: String?): ApiResponse<CharacterDto>

    @GET("episode")
    suspend fun getEpisodes(@Query("name") name: String?): ApiResponse<EpisodeDto>

    @GET("location")
    suspend fun getLocations(@Query("name") name: String?): ApiResponse<LocationDto>
}

object RickAndMortyService {
    private val json = Json { ignoreUnknownKeys = true }

    val api: RickAndMortyApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(RickAndMortyApi::class.java)
    }
}

// ───────────────────────── VIEWMODEL ─────────────────────────

enum class Category(val label: String) {
    CHARACTER("Character"), EPISODE("Episode"), LOCATION("Location")
}

sealed interface ExplorerUiState {
    object Loading : ExplorerUiState
    object Empty : ExplorerUiState
    data class Error(val message: String) : ExplorerUiState
    data class Success(val total: Int, val retrieved: Int, val item: DisplayItem) : ExplorerUiState
}

class ExplorerViewModel : ViewModel() {

    var category by mutableStateOf(Category.CHARACTER)
        private set
    var query by mutableStateOf("")
        private set
    var uiState: ExplorerUiState by mutableStateOf(ExplorerUiState.Loading)
        private set

    private var items: List<DisplayItem> = emptyList()
    private var total = 0
    private var searchJob: Job? = null

    init { load() }   // random item every time the app opens

    fun onCategoryChange(newCategory: Category) {
        if (newCategory == category) return
        category = newCategory
        query = ""
        load()
    }

    fun onQueryChange(newQuery: String) {
        query = newQuery
        load(debounce = true)
    }

    fun roll() {
        val current = (uiState as? ExplorerUiState.Success)?.item
        val pool = if (items.size > 1) items.filter { it != current } else items
        if (pool.isNotEmpty()) {
            uiState = ExplorerUiState.Success(total, items.size, pool.random())
        }
    }

    fun retry() = load()

    private fun load(debounce: Boolean = false) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            if (debounce) delay(400)   // wait until the user stops typing
            uiState = ExplorerUiState.Loading
            try {
                val name = query.trim().ifBlank { null }
                val api = RickAndMortyService.api
                when (category) {
                    Category.CHARACTER -> api.getCharacters(name).let { r ->
                        total = r.info.count; items = r.results.map { it.toDisplay() }
                    }
                    Category.EPISODE -> api.getEpisodes(name).let { r ->
                        total = r.info.count; items = r.results.map { it.toDisplay() }
                    }
                    Category.LOCATION -> api.getLocations(name).let { r ->
                        total = r.info.count; items = r.results.map { it.toDisplay() }
                    }
                }
                uiState = if (items.isEmpty()) ExplorerUiState.Empty
                else ExplorerUiState.Success(total, items.size, items.random())
            } catch (e: HttpException) {
                // The API answers 404 when the filter matches nothing
                items = emptyList()
                uiState = if (e.code() == 404) ExplorerUiState.Empty
                else ExplorerUiState.Error("Server error ${e.code()}")
            } catch (e: IOException) {
                uiState = ExplorerUiState.Error("No internet connection")
            } catch (e: Exception) {
                uiState = ExplorerUiState.Error(e.message ?: "Unknown error")
            }
        }
    }
}

// ───────────────────────── UI ─────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExplorerScreen(vm: ExplorerViewModel = viewModel()) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("Rick and Morty Explorer") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Select Category", fontWeight = FontWeight.Bold)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Category.values().forEach { c ->
                    FilterChip(
                        selected = vm.category == c,
                        onClick = { vm.onCategoryChange(c) },
                        label = { Text(c.label) }
                    )
                }
            }

            OutlinedTextField(
                value = vm.query,
                onValueChange = vm::onQueryChange,
                label = { Text("Filter ${vm.category.label} by name...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            when (val state = vm.uiState) {
                is ExplorerUiState.Loading -> Box(
                    Modifier.fillMaxWidth().height(200.dp),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }

                is ExplorerUiState.Empty -> Text(
                    "Nothing found for \"${vm.query}\".",
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    textAlign = TextAlign.Center
                )

                is ExplorerUiState.Error -> Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(state.message, color = MaterialTheme.colorScheme.error)
                    Button(onClick = vm::retry) { Text("Retry") }
                }

                is ExplorerUiState.Success -> {
                    StatsCard(vm.category, state.total, state.retrieved)
                    ItemCard(state.item)
                }
            }

            Button(
                onClick = vm::roll,
                enabled = vm.uiState is ExplorerUiState.Success,
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Roll Random Item")
            }
        }
    }
}

@Composable
private fun StatsCard(category: Category, total: Int, retrieved: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("API Stats", fontWeight = FontWeight.Bold)
            Text("Total ${category.label}s in API: $total")
            Text("Retrieved in current view: $retrieved items")
        }
    }
}

@Composable
private fun ItemCard(item: DisplayItem) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (item.imageUrl != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(item.imageUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = item.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(220.dp).clip(RoundedCornerShape(16.dp))
                )
            } else {
                Box(
                    Modifier.size(220.dp).clip(RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) { Text("No image") }
            }

            Text(item.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                LabeledValue(item.label1, item.value1)
                LabeledValue(item.label2, item.value2)
            }
        }
    }
}

@Composable
private fun LabeledValue(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

// ───────────────────────── ACTIVITY ─────────────────────────

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ExplorerScreen()
                }
            }
        }
    }
}