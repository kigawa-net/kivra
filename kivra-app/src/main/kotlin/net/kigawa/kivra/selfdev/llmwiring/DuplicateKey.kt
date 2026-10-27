package net.kigawa.kivra.selfdev.llmwiring

import java.sql.SQLException

/** True for a unique or primary key violation (MariaDB 1062, H2 23505), raw or wrapped. The raw JDBC calls of these stores throw a plain SQLException. */
internal fun Throwable.isDuplicateKey(): Boolean = generateSequence(this) { it.cause }.take(MAX_CAUSE_DEPTH).any {
    it is SQLException && (it.sqlState == "23505" || it.errorCode == 1062 || it.errorCode == 23505)
}

private const val MAX_CAUSE_DEPTH = 6
