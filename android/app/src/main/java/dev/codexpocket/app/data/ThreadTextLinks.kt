package dev.codexpocket.app.data

data class ThreadTextLink(val start: Int, val end: Int, val nativeId: String, val label: String?) {
    val url get() = "codex://threads/$nativeId"
}

/** Recognize conversation references without interpreting snippets, file paths or arbitrary URIs. */
fun threadTextLinks(text: String, protected: List<IntRange> = emptyList()): List<ThreadTextLink> {
    val id = "[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"
    val code = mutableListOf<IntRange>()
    var scan=0
    while(scan<text.length) {
        val mark=text[scan]
        if(mark!='`' && mark!='~') {scan++;continue}
        var count=1
        while(scan+count<text.length && text[scan+count]==mark) count++
        if(mark=='~' && count<3) {scan+=count;continue}
        val delimiter=mark.toString().repeat(count)
        var end=text.indexOf(delimiter,scan+count)
        while(end>=0 && (text.getOrNull(end-1)==mark || text.getOrNull(end+count)==mark)) end=text.indexOf(delimiter,end+count)
        val until=if(end>=0) end+count else if(count>=3) text.length else text.indexOf('\n',scan+count).takeIf {it>=0} ?: text.length
        code+=scan until until
        scan=until
    }
    val markdown = Regex("\\[([^\\]\\n]*)]\\(([^\\s)]+)\\)").findAll(text).toList()
    fun overlaps(range: IntRange, ranges: List<IntRange>) = ranges.any {range.first <= it.last && it.first <= range.last}
    val links = markdown.mapNotNull {match ->
        if(overlaps(match.range, code + protected)) null else ThreadDeepLink.parse(match.groupValues[2])?.let {
            ThreadTextLink(match.range.first, match.range.last + 1, it.nativeId, match.groupValues[1].takeIf(String::isNotBlank))
        }
    }
    val reserved = code + protected + markdown.map {it.range}
    val candidates = Regex("(?i:codex://threads/$id)|$id").findAll(text).mapNotNull {match ->
        val before = text.getOrNull(match.range.first - 1)
        val after = text.getOrNull(match.range.last + 1)
        if(overlaps(match.range, reserved) || before?.let {it.isLetterOrDigit() || it in "_/-\\\\:"} == true || after?.let {it.isLetterOrDigit() || it in "_/?#"} == true) null
        else ThreadDeepLink.parse(if(match.value.startsWith("codex:",true)) match.value else "codex://threads/${match.value}")?.let {
            ThreadTextLink(match.range.first, match.range.last + 1, it.nativeId, null)
        }
    }.toList()
    return (links + candidates).sortedBy {it.start}
}
