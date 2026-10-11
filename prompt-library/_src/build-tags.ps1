$ErrorActionPreference = "Stop"

$src = "d:\Desktop\Projects\App Projects\local-dream-frontend\prompt-library\_src\zh_CN.yaml"
$out = "d:\Desktop\Projects\App Projects\local-dream-frontend\prompt-library\localdream-tags.txt"

# 直接沿用源库的分类结构：一级 / 二级 作为分类路径
$rows = New-Object System.Collections.Generic.List[object]
$seen = New-Object System.Collections.Generic.HashSet[string]
$c1 = ""
$c2 = ""

foreach ($l in (Get-Content $src -Encoding UTF8)) {
    if ($l -match '^\s*#') { continue }
    if ($l -match '^- name:\s*(.+?)\s*$') { $c1 = $matches[1].Trim('"').Trim(); continue }
    if ($l -match '^\s{4}- name:\s*(.+?)\s*$') { $c2 = $matches[1].Trim('"').Trim(); continue }
    if ($l -notmatch '^\s{8}(\S[^:]*?):\s*(.*?)\s*$') { continue }

    $en = $matches[1].Trim('"')
    $zh = $matches[2].Trim('"')
    if (-not $en -or $en -eq 'tags') { continue }

    # 源库用 | 分隔同义词，会撞上 "分类 | 中文 | 英文" 的列分隔符，换成顿号
    $zh = $zh -replace '\|', '、'

    # 源库里像 "唐风:" / "宋风:" 这样的分组名带尾冒号，去掉
    $second = $c2.TrimEnd(':').Trim()
    $cat = if ($second) { "$c1/$second" } else { $c1 }
    if (-not $cat) { continue }

    if (-not $seen.Add("$cat|$en")) { continue }
    $rows.Add([PSCustomObject]@{ Cat = $cat; Zh = $zh; En = $en })
}

# 保持源文件顺序：源库是按常用度排列的（比如"上衣"很靠前），
# 按字母重排会把这些常用标签挤到很后面
$lines = $rows | ForEach-Object { "$($_.Cat) | $($_.Zh) | $($_.En)" }
[System.IO.File]::WriteAllLines($out, $lines, (New-Object System.Text.UTF8Encoding($false)))

Write-Output "总共写入 $($lines.Count) 条"
Write-Output "--- 各分类条数 ---"
$rows | Group-Object Cat | Sort-Object Name | ForEach-Object { "{0,-28} {1,5}" -f $_.Name, $_.Count }
