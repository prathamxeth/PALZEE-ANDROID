package com.finrein.pals.core.data.repository

import com.finrein.pals.core.domain.model.ActivePalState
import com.finrein.pals.core.domain.model.MessageDbItem
import com.finrein.pals.core.domain.model.PalDbItem
import com.finrein.pals.core.domain.model.SubmissionDbItem
import com.finrein.pals.core.domain.model.UserPalMapping
import com.finrein.pals.core.domain.repository.ActivePalRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ActivePalRepositoryImpl @Inject constructor(
    private val supabaseClient: SupabaseClient
) : ActivePalRepository {

    override suspend fun getActivePalDetails(
        palCode: String,
        currentUserId: String,
        currentDisplayName: String,
        firstName: String,
        currentAvatarUrl: String?,
        locallyDeletedSubmissions: Set<String>,
        syncMutex: kotlinx.coroutines.sync.Mutex,
        resolveAvatarUrl: suspend () -> String?
    ): Result<ActivePalState> = withContext(Dispatchers.IO) {
        runCatching {
            // Groups strictly operate within the absolute, real-time 4am to 4am day window
            val systemNow = java.time.ZonedDateTime.now(java.time.ZoneId.systemDefault())
            val targetDay = if (systemNow.hour < 4) {
                systemNow.minusDays(1).format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
            } else {
                systemNow.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
            }
            
            val currentSystemHour = systemNow.hour

            // Resolve local 4am time to UTC instant based on the system's timezone offset
            val localEnd = java.time.LocalDate.parse(targetDay)
                .plusDays(1)
                .atTime(4, 0)
                .atZone(java.time.ZoneId.systemDefault())
            val localStart = localEnd.minusDays(8)
            val startUtc = localStart.toInstant().toString()
            val endUtc = localEnd.toInstant().toString()

            // 1. Fetch only submissions belonging strictly to this custom palCode group table row
            val dbSubmissions = supabaseClient.postgrest.from("submissions")
                .select {
                    filter {
                        eq("pal_code", palCode)
                    }
                    order("created_at", io.github.jan.supabase.postgrest.query.Order.DESCENDING)
                }
                .decodeList<SubmissionDbItem>()

            // 2. Fetch messages
            val dbMessages = supabaseClient.postgrest.from("messages")
                .select {
                    filter {
                        eq("pal_code", palCode)
                    }
                    order("created_at", io.github.jan.supabase.postgrest.query.Order.ASCENDING)
                }
                .decodeList<MessageDbItem>()

            // 3. Check for profile submission
            var finalSubmissionsList = dbSubmissions
            val hasUserSub = dbSubmissions.any { it.userId == currentUserId }
            if (!hasUserSub) {
                val avatarUrl = resolveAvatarUrl() ?: ""
                val cleanCode = palCode.trim()
                if (cleanCode.isNotEmpty()) {
                    val profileDisplayName = if (avatarUrl.isNotEmpty()) "$firstName|||$avatarUrl" else firstName
                    val profileSub = SubmissionDbItem(
                        palCode = cleanCode,
                        userId = currentUserId,
                        userDisplayName = profileDisplayName,
                        imageUrl = "PROFILE_AVATAR",
                        createdAt = java.time.Instant.now().toString()
                    )

                    // Use the mutex lock here so profile generation never cross-fires with real-time events
                    if (!syncMutex.isLocked) {
                        syncMutex.lock()
                        try {
                            val existingPal = try {
                                supabaseClient.postgrest.from("pals")
                                    .select { filter { eq("pal_code", cleanCode) } }
                                    .decodeSingleOrNull<PalDbItem>()
                            } catch (e: Exception) {
                                null
                            }

                            if (existingPal == null) {
                                try {
                                    supabaseClient.postgrest.from("pals")
                                        .insert(PalDbItem(code = cleanCode, name = "Pals Group"))
                                } catch (e: Exception) {
                                    // Ignore conflict to preserve original group name
                                }
                            }
                        } finally {
                            syncMutex.unlock()
                        }
                    }
                }
            }

            val filteredSubmissions = finalSubmissionsList.filterNot { sub ->
                sub.imageUrl == "PROFILE_AVATAR" || sub.imageUrl.startsWith("PROFILE_AVATAR") ||
                locallyDeletedSubmissions.contains(sub.imageUrl.split("|||").firstOrNull() ?: "") ||
                (sub.id != null && locallyDeletedSubmissions.contains(sub.id.toString()))
            }

            // 4. Fetch mappings
            val mappings = supabaseClient.postgrest.from("user_pals")
                .select {
                    filter {
                        eq("pal_code", palCode)
                    }
                }
                .decodeList<UserPalMapping>()
                .sortedWith(compareBy({ it.createdAt ?: "" }, { it.id ?: "" }))

            val userFirstName = currentDisplayName.trim().substringBefore(" ").substringBefore("_").substringBefore(".")
            
            val memberList = mutableListOf<String>()
            val addedUserIds = mutableSetOf<String>()

            mappings.forEach { mapping ->
                if (mapping.userId.isNotEmpty() && !addedUserIds.contains(mapping.userId)) {
                    val sub = finalSubmissionsList.firstOrNull { it.userId == mapping.userId }
                    val (displayName, avatarUrl) = if (sub != null) {
                        parseUserDisplayName(sub.userDisplayName)
                    } else {
                        if (mapping.userId == currentUserId) {
                            val localAvatar = if (currentAvatarUrl?.startsWith("http") == true) currentAvatarUrl else null
                            Pair(userFirstName, localAvatar)
                        } else {
                            val dispName = mapping.userDisplayName
                            if (!dispName.isNullOrEmpty()) {
                                val parsed = parseUserDisplayName(dispName)
                                Pair(parsed.first, parsed.second ?: mapping.userAvatarUrl)
                            } else {
                                Pair("Pal", null)
                            }
                        }
                    }
                    val formatted = "${mapping.userId}|||$displayName|||${avatarUrl ?: ""}"
                    memberList.add(formatted)
                    addedUserIds.add(mapping.userId)
                }
            }

            finalSubmissionsList.forEach { sub ->
                if (sub.userId.isNotEmpty() && !addedUserIds.contains(sub.userId)) {
                    val (displayName, avatarUrl) = parseUserDisplayName(sub.userDisplayName)
                    val formatted = "${sub.userId}|||$displayName|||${avatarUrl ?: ""}"
                    memberList.add(formatted)
                    addedUserIds.add(sub.userId)
                }
            }

            val dailyHourHistoryMap = filteredSubmissions.groupBy { it.getHourBucket() }
            val itemsInThisHour = dailyHourHistoryMap[currentSystemHour] ?: emptyList()
            val activeHourSubmissions = itemsInThisHour.associateBy { it.userId }
            val exportMenuDataState = dailyHourHistoryMap.toSortedMap()
            val memberCount = filteredSubmissions.map { it.userId }.distinct().size

            ActivePalState(
                palCode = palCode,
                submissions = filteredSubmissions,
                messages = dbMessages,
                members = memberList,
                dailyHourHistory = dailyHourHistoryMap,
                activeHourSubmissions = activeHourSubmissions,
                exportData = exportMenuDataState,
                memberCount = memberCount
            )
        }
    }

    private fun parseUserDisplayName(userDisplayName: String): Pair<String, String?> {
        val parts = userDisplayName.split("|||")
        val rawName = parts.getOrNull(0) ?: ""
        val cleanName = rawName.trim().substringBefore(" ").substringBefore("_").substringBefore(".")
        val avatar = parts.getOrNull(1)
        return Pair(cleanName, if (avatar.isNullOrEmpty()) null else avatar)
    }
}
