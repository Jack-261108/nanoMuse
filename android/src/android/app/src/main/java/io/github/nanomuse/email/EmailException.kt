package io.github.nanomuse.email

/** A failure whose message is safe to hand to the model and the person: never a secret. */
class EmailException(message: String) : Exception(message)
