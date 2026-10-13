package net.kigawa.kivra.db

import net.kigawa.kivra.core.ProviderCapability
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.transaction

/** The Authority Epoch values for one (tenant, provider) as recorded in Kivra MariaDB. */
data class AuthorityEpochSnapshot(
    val providerContractVersion: String,
    val capability: ProviderCapability,
    val capabilityRevision: Long,
    val authorityEpoch: Long,
    val grantGeneration: Long,
)

/**
 * Kivra MariaDB copy of the Trusted Evidence Authority's current epoch (Issue #94, table `evidence_authority_epochs`).
 *
 * - [publish] writes the copy. The Authority sync is the intended caller; it is not implemented in this slice, so the
 *   tests call it directly.
 * - [lockForTransition] is used by [OperationJournalRepository] inside the transition transaction. It takes a row lock
 *   (`SELECT ... FOR UPDATE`) so that a concurrent [publish] and a transition are serialized on this row. The commit
 *   therefore sees either the old epoch or the new one, never a mix.
 *
 * Not a cross-store guarantee. The value compared is the copy in Kivra MariaDB, not the Authority's own state. An
 * Authority change that has not been synced here is invisible to the commit. A Permit / Revocation ACK protocol with a
 * short TTL would be needed to bound that window; it is a separate design and is not implemented here.
 */
class AuthorityEpochRepository(private val database: Database) {

    fun publish(tenantId: String, providerId: String, snapshot: AuthorityEpochSnapshot) {
        transaction(database) {
            val updated = execUpdate(
                """
                UPDATE evidence_authority_epochs
                   SET provider_contract_version = ?, capability = ?, capability_revision = ?,
                       authority_epoch = ?, grant_generation = ?, updated_at = NOW(6)
                 WHERE tenant_id = ? AND provider_id = ?
                """.trimIndent(),
                snapshot.providerContractVersion, snapshot.capability.name, snapshot.capabilityRevision,
                snapshot.authorityEpoch, snapshot.grantGeneration, tenantId, providerId,
            )
            if (updated == 0) {
                execUpdate(
                    """
                    INSERT INTO evidence_authority_epochs
                        (tenant_id, provider_id, provider_contract_version, capability, capability_revision,
                         authority_epoch, grant_generation, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, NOW(6))
                    """.trimIndent(),
                    tenantId, providerId, snapshot.providerContractVersion, snapshot.capability.name,
                    snapshot.capabilityRevision, snapshot.authorityEpoch, snapshot.grantGeneration,
                )
            }
        }
    }

    fun current(tenantId: String, providerId: String): AuthorityEpochSnapshot? = transaction(database) {
        readEpoch(tenantId, providerId, lock = false)
    }

    /** Row-locked read for use inside a transition transaction. Returns null when no copy exists (fail-closed). */
    internal fun Transaction.lockForTransition(tenantId: String, providerId: String): AuthorityEpochSnapshot? =
        readEpoch(tenantId, providerId, lock = true)

    private fun Transaction.readEpoch(tenantId: String, providerId: String, lock: Boolean): AuthorityEpochSnapshot? {
        val sql = """
            SELECT provider_contract_version, capability, capability_revision, authority_epoch, grant_generation
              FROM evidence_authority_epochs
             WHERE tenant_id = ? AND provider_id = ?
            """.trimIndent() + if (lock) " FOR UPDATE" else ""
        return execQuery(sql, tenantId, providerId) {
            AuthorityEpochSnapshot(
                providerContractVersion = it.getString(1),
                capability = ProviderCapability.valueOf(it.getString(2)),
                capabilityRevision = it.getLong(3),
                authorityEpoch = it.getLong(4),
                grantGeneration = it.getLong(5),
            )
        }.singleOrNull()
    }
}
