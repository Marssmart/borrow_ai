package org.deer.borrowai.data.app

enum class AppMode {
    DEMO,
    REAL;

    companion object {
        fun fromStartupFlag(flag: String?): AppMode =
            if (flag?.trim()?.equals(DEMO_FLAG, ignoreCase = true) == true) DEMO else REAL

        private const val DEMO_FLAG = "demo"
    }
}
