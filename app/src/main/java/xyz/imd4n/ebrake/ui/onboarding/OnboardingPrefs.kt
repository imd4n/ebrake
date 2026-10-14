package xyz.imd4n.ebrake.ui.onboarding

import android.content.Context

/**
 * Флаг прохождения онбординга. Показывается один раз на первом запуске:
 * дальше пользователь уже выбрал папку и раздал разрешения, спрашивать
 * заново незачем.
 */
object OnboardingPrefs {
    private const val PREFS = "ebrake_onboarding"
    private const val KEY_DONE = "done"

    fun isDone(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_DONE, false)

    fun setDone(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DONE, true)
            .apply()
    }
}
