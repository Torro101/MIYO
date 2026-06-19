package org.koharu.miyo.core.exceptions

tailrec fun Throwable?.findCloudFlareCause(): CloudFlareException? = when (this) {
	is CloudFlareException -> this
	is CaughtException -> cause.findCloudFlareCause()
	is WrapperIOException -> cause.findCloudFlareCause()
	is java.io.IOException -> cause.findCloudFlareCause()
	else -> null
}

fun Throwable?.findCloudFlareProtectedCause(): CloudFlareProtectedException? {
	return findCloudFlareCause() as? CloudFlareProtectedException
}
