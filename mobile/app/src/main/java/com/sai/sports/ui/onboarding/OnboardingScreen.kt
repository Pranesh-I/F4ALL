package com.sai.sports.ui.onboarding

import android.app.Activity
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.sai.sports.R
import com.sai.sports.i18n.AppLanguage
import com.sai.sports.i18n.LanguageStore
import com.sai.sports.ui.kit.AppIcons
import com.sai.sports.ui.kit.GlowBackdrop
import com.sai.sports.ui.kit.MarigoldButton
import com.sai.sports.ui.kit.PlainButton
import com.sai.sports.ui.theme.AppColors
import com.sai.sports.ui.theme.AppFonts

/**
 * First run: language, then three short pages, then the welcome screen.
 *
 * Language is the very first thing asked, before a single sentence of English,
 * and each option is written in its own script — an athlete who cannot read
 * English must still be able to get past this screen.
 *
 * The pages say the three things an athlete most needs before recording
 * anything: what the app is for, that the phone's score is not the official one,
 * and who sees their videos.
 */
@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    val context = LocalContext.current

    // Page 0 is the language picker; survives the recreate a language change causes.
    var page by rememberSaveable { mutableIntStateOf(if (LanguageStore.current(context) == null) 0 else 1) }

    if (page == 0) {
        LanguagePage(
            onSelected = { language ->
                LanguageStore.set(context, language)
                page = 1
                (context as? Activity)?.recreate()
            }
        )
        return
    }

    val pages = listOf(
        IntroPage(R.drawable.ic_intro_record, R.string.onboarding_1_title, R.string.onboarding_1_body),
        IntroPage(R.drawable.ic_intro_verified, R.string.onboarding_2_title, R.string.onboarding_2_body),
        IntroPage(R.drawable.ic_intro_privacy, R.string.onboarding_3_title, R.string.onboarding_3_body)
    )
    val index = (page - 1).coerceIn(0, pages.lastIndex)

    fun finish() {
        OnboardingStore.markComplete(context)
        onFinished()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(AppColors.Background)
    ) {
        // The art: the page's drawing in a ringed disc, lit from the corner.
        GlowBackdrop(
            Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            Row(
                Modifier
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End
            ) {
                if (index < pages.lastIndex) {
                    PlainButton(text = stringResource(R.string.onboarding_skip), onClick = ::finish)
                }
            }
            AnimatedContent(index, Modifier.align(Alignment.Center), { fadeIn() togetherWith fadeOut() }, label = "onboarding art") { shown ->
                Box(
                    Modifier
                        .size(220.dp)
                        .border(1.dp, AppColors.Accent.copy(alpha = 0.5f), CircleShape)
                        .padding(18.dp)
                        .background(AppColors.Surface, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(pages[shown].image),
                        // Decorative: the heading and body carry the meaning.
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(AppColors.TextPrimary),
                        modifier = Modifier.size(110.dp)
                    )
                }
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            PageDots(count = pages.size, current = index)
            AnimatedContent(index, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "onboarding text") { shown ->
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        stringResource(pages[shown].title),
                        style = MaterialTheme.typography.headlineLarge,
                        color = AppColors.TextPrimary,
                        modifier = Modifier.semantics { heading() }
                    )
                    Text(
                        stringResource(pages[shown].body),
                        style = MaterialTheme.typography.bodyLarge,
                        color = AppColors.TextSecondary,
                        modifier = Modifier.heightIn(min = 96.dp)
                    )
                }
            }
            MarigoldButton(
                text = stringResource(if (index == pages.lastIndex) R.string.onboarding_start else R.string.action_next),
                onClick = { if (index == pages.lastIndex) finish() else page += 1 },
                icon = if (index == pages.lastIndex) null else AppIcons.Forward,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** Which page of the introduction this is: the current one a longer orange bar. */
@Composable
private fun PageDots(count: Int, current: Int) {
    val spoken = stringResource(R.string.onboarding_step, current + 1, count)
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.clearAndSetSemantics { contentDescription = spoken }
    ) {
        repeat(count) { index ->
            val width by animateDpAsState(if (index == current) 28.dp else 8.dp, label = "page dot")
            Box(
                Modifier
                    .width(width)
                    .height(8.dp)
                    .background(
                        if (index == current) AppColors.Accent else AppColors.SurfaceHighest,
                        RoundedCornerShape(4.dp)
                    )
            )
        }
    }
}

/** "Choose your language", in each language, for the very first screen. */
private val LANGUAGE_PROMPTS = listOf(
    AppLanguage.ENGLISH to "Choose your language",
    AppLanguage.HINDI to "अपनी भाषा चुनें",
    AppLanguage.TAMIL to "உங்கள் மொழியைத் தேர்ந்தெடுக்கவும்",
    AppLanguage.BENGALI to "আপনার ভাষা বেছে নিন"
)

private data class IntroPage(
    @DrawableRes val image: Int,
    @StringRes val title: Int,
    @StringRes val body: Int
)

@Composable
private fun LanguagePage(onSelected: (AppLanguage) -> Unit) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxSize()
            .background(AppColors.Background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Box(
            Modifier
                .size(64.dp)
                .background(AppColors.AccentSoft, RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(AppIcons.Globe, contentDescription = null, tint = AppColors.Accent, modifier = Modifier.size(32.dp))
        }
        // Deliberately multilingual: the athlete has not chosen yet. Each line
        // is set in its own script's face, so none of them looks foreign.
        Column(
            modifier = Modifier.semantics(mergeDescendants = true) { heading() },
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            LANGUAGE_PROMPTS.forEachIndexed { index, (language, prompt) ->
                Text(
                    text = prompt,
                    style = (if (index == 0) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleMedium)
                        .copy(fontFamily = AppFonts.text(language)),
                    color = if (index == 0) AppColors.TextPrimary else AppColors.TextSecondary
                )
            }
        }
        LanguagePicker(selected = LanguageStore.current(context), onSelected = onSelected)
    }
}

/**
 * One card per language, each labelled in its own script and set in that
 * script's face, so a Tamil reader sees Tamil that looks like Tamil, whatever
 * language the screen is in.
 */
@Composable
fun LanguagePicker(selected: AppLanguage?, onSelected: (AppLanguage) -> Unit) {
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AppLanguage.entries.forEach { language ->
            val chosen = language == selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(AppColors.Surface)
                    .border(
                        1.5.dp,
                        if (chosen) AppColors.Accent else AppColors.Surface,
                        RoundedCornerShape(16.dp)
                    )
                    .selectable(selected = chosen, role = Role.RadioButton, onClick = { onSelected(language) })
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    language.nativeName,
                    style = MaterialTheme.typography.titleLarge.copy(fontFamily = AppFonts.text(language)),
                    color = AppColors.TextPrimary,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(12.dp))
                Box(
                    Modifier
                        .size(26.dp)
                        .background(if (chosen) AppColors.Accent else AppColors.SurfaceHigh, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (chosen) {
                        Icon(AppIcons.Check, contentDescription = null, tint = AppColors.OnAccent, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}
