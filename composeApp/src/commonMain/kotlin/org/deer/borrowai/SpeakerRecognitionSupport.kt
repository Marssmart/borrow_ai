package org.deer.borrowai

/**
 * Whether this platform offers speaker recognition. iOS hides it in v1 (section 12, Q-5), so its speaker chips, detail
 * section, and Settings rows are left out.
 */
internal expect val speakerRecognitionSupported: Boolean
