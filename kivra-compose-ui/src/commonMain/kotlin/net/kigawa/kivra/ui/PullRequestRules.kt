package net.kigawa.kivra.ui

/** GitHub の PR の URL の形(旧 UI の PR_URL と同じ): `https://github.com/<owner>/<repo>/pull/<番号>`。 */
private val PULL_REQUEST_URL = Regex("https://github\\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/pull/[0-9]+")

/**
 * 開くことを許す PR の URL。形が合う時だけ返し、合わなければ null(リンクにしない)。
 * 旧 UI と同じく、リポジトリ名との照合は行わない(Run の pullRequestRepository は表示のみ)。
 */
fun safePullRequestUrl(url: String?): String? = url?.takeIf { PULL_REQUEST_URL.matches(it) }
