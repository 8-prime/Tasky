package dev.tasky.android.ui.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.tasky.android.data.SessionStore
import dev.tasky.android.data.TaskyRepository
import dev.tasky.android.data.db.ListRow
import dev.tasky.android.data.db.MealRow
import dev.tasky.android.data.db.MemberRow
import dev.tasky.android.data.db.PlanEntryRow
import dev.tasky.android.domain.MealPlanning
import dev.tasky.android.domain.ProposedContribution
import dev.tasky.android.ui.list.DEFAULT_LIST_NAME
import dev.tasky.api.MealSlot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

data class EntryView(
    val row: PlanEntryRow,
    val title: String,
    /** Null for a household meal. */
    val ownerName: String?,
    val servings: Int,
    val hasIngredients: Boolean,
    /** Contributions on a list that came from this entry. */
    val onListCount: Int,
)

data class DayPlan(val date: LocalDate, val slots: Map<MealSlot, List<EntryView>>)

data class PlanState(
    val weekStart: LocalDate,
    val days: List<DayPlan> = emptyList(),
    val meals: List<MealRow> = emptyList(),
    val members: List<MemberRow> = emptyList(),
    val lists: List<ListRow> = emptyList(),
    val myUserId: String? = null,
)

/** One row of the "add to list" checklist. */
data class ProposalRow(val proposal: ProposedContribution, val alreadyAdded: Boolean)

@OptIn(ExperimentalCoroutinesApi::class)
class PlanViewModel(private val repo: TaskyRepository, private val sessions: SessionStore) : ViewModel() {
    private val q = repo.queries
    private val _week = MutableStateFlow(MealPlanning.weekStart(LocalDate.now()))
    val week: StateFlow<LocalDate> = _week.asStateFlow()

    private val entries = _week.flatMapLatest { start ->
        q.planEntries(start.toString(), start.plusDays(6).toString())
    }

    private val catalog = combine(q.meals(), q.allIngredients(), q.members(), q.lists(), q.planContributions()) { meals, ings, members, lists, contributions ->
        Catalog(meals, ings.groupBy { it.mealId }.mapValues { it.value.size }, members, lists, contributions.groupingBy { it.planEntryId!! }.eachCount())
    }

    private data class Catalog(
        val meals: List<MealRow>,
        val ingredientCounts: Map<String, Int>,
        val members: List<MemberRow>,
        val lists: List<ListRow>,
        val onList: Map<String, Int>,
    )

    val state: StateFlow<PlanState> = combine(_week, entries, catalog) { start, entries, c ->
        val meals = c.meals.associateBy { it.id }
        val names = c.members.associate { it.userId to it.displayName }
        val memberCount = c.members.size
        val views = entries.map { e ->
            val meal = e.mealId?.let(meals::get)
            EntryView(
                row = e,
                title = meal?.name ?: e.freeText.orEmpty(),
                ownerName = e.ownerUserId?.let { names[it] ?: "?" },
                servings = MealPlanning.servings(e, memberCount),
                hasIngredients = meal != null && (c.ingredientCounts[meal.id] ?: 0) > 0,
                onListCount = c.onList[e.id] ?: 0,
            )
        }
        PlanState(
            weekStart = start,
            days = MealPlanning.weekDays(start).map { day ->
                val forDay = views.filter { it.row.date == day.toString() }
                DayPlan(day, MealSlot.entries.associateWith { slot -> forDay.filter { it.row.slot == slot.string }.sortedBy { it.title.lowercase() } })
            },
            meals = c.meals,
            members = c.members,
            lists = c.lists.sortedWith(compareBy({ !it.name.equals(DEFAULT_LIST_NAME, true) }, { it.name.lowercase() })),
            myUserId = sessions.current?.userId,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlanState(_week.value))

    fun previousWeek() { _week.value = _week.value.minusWeeks(1) }
    fun nextWeek() { _week.value = _week.value.plusWeeks(1) }
    fun thisWeek() { _week.value = MealPlanning.weekStart(LocalDate.now()) }

    fun save(existing: PlanEntryRow?, input: TaskyRepository.PlanEntryInput) = viewModelScope.launch {
        if (existing == null) repo.createPlanEntry(input) else repo.updatePlanEntry(existing, input)
    }

    fun delete(entry: PlanEntryRow) = viewModelScope.launch { repo.deletePlanEntry(entry.id) }

    /** The checklist for one entry, or the whole week if [entry] is null. */
    suspend fun proposals(entry: PlanEntryRow?): List<ProposalRow> {
        val entries = if (entry != null) listOf(entry) else entries.first()
        val proposals = MealPlanning.propose(
            entries = entries,
            meals = q.meals().first().associateBy { it.id },
            ingredientsByMeal = q.allIngredients().first().groupBy { it.mealId },
            items = q.items().first().associateBy { it.id },
            memberCount = q.memberCount(),
        )
        val existing = repo.existingContributions(proposals.map { it.id })
        return proposals.map { ProposalRow(it, it.id in existing) }
    }

    fun addToList(listId: String, proposals: List<ProposedContribution>) = viewModelScope.launch {
        repo.addToList(listId, proposals)
    }
}
