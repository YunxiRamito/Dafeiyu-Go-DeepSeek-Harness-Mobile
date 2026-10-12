#requires -Version 7.0
# Pure observations and bounded navigation. Device operations are injected by the owner.
Set-StrictMode -Version Latest

function Get-QaChatPixels([string]$PngPath, [int[]]$Bounds) {
    Assert-Qa ((Get-Item -LiteralPath $PngPath).Length -le 4MB) 'PNG exceeds 4 MiB budget'
    Add-Type -AssemblyName System.Drawing
    $image = [Drawing.Bitmap]::new($PngPath)
    try {
        Assert-Qa ($image.Width -le 4096 -and $image.Height -le 4096 -and
            $Bounds.Count -eq 4 -and $Bounds[0] -ge 0 -and $Bounds[1] -ge 0 -and
            $Bounds[2] -le $image.Width -and $Bounds[3] -le $image.Height -and
            $Bounds[2] -gt $Bounds[0] -and $Bounds[3] -gt $Bounds[1]) 'PNG/surface dimensions invalid'
        # Sample only the chat interior; clocks, composer and navigation do not indicate scroll.
        $pixels = [int[]]::new(48 * 64)
        for ($row = 0; $row -lt 64; $row++) {
            for ($column = 0; $column -lt 48; $column++) {
                $x = [int]($Bounds[0] + ($Bounds[2] - $Bounds[0]) * (.06 + .88 * ($column + .5) / 48))
                $y = [int]($Bounds[1] + ($Bounds[3] - $Bounds[1]) * (.03 + .94 * ($row + .5) / 64))
                $pixels[$row * 48 + $column] = $image.GetPixel($x, $y).ToArgb()
            }
        }
        return ,$pixels
    } finally { $image.Dispose() }
}

function Compare-QaChatPixels([int[]]$Before, [int[]]$After) {
    Assert-Qa ($Before.Count -eq 3072 -and $After.Count -eq 3072) 'Pixel sample count invalid'
    $changed = 0
    for ($i = 0; $i -lt $Before.Count; $i++) {
        $a = [Drawing.Color]::FromArgb($Before[$i])
        $b = [Drawing.Color]::FromArgb($After[$i])
        if ([Math]::Max([Math]::Abs([int]$a.R - $b.R), [Math]::Max(
            [Math]::Abs([int]$a.G - $b.G), [Math]::Abs([int]$a.B - $b.B))) -gt 12) { $changed++ }
    }
    return $changed / [double]$Before.Count
}

function Get-QaLongObservation([xml]$Document, $Messages) {
    Assert-QaChat $Document 'QA 019 bounded long chat' @()
    $surface = Get-QaChatSurface $Document
    Assert-Qa ($null -ne $surface) 'Long chat has no validated scroll surface'
    $bounds = Get-QaBounds $surface
    $rounds = [Collections.Generic.HashSet[int]]::new()
    $firstCandidate = $false
    $lastSemantic = $false
    foreach ($node in $surface.SelectNodes('.//node')) {
        if (-not (Test-QaNodeInSurface $node $surface)) { continue }
        $text = $node.GetAttribute('text')
        # Match frozen full message bytes, not arbitrary sentinel substrings.
        for ($i = 0; $i -lt $Messages.Count; $i++) {
            # The frozen fixture uses plain Markdown paragraphs: the saved accessibility
            # text trims each soft line and joins it with a space. Bind the complete
            # rendered paragraph as well as raw bytes, never a sentinel substring.
            $rendered = (($Messages[$i].text -split '\r\n|\n|\r' | ForEach-Object { $_.Trim() }) -join ' ')
            if ($text -cne $Messages[$i].text -and $text -cne $rendered) { continue }
            $null = $rounds.Add([int][Math]::Floor($i / 2))
            $b = Get-QaBounds $node
            if ($i -eq 0 -and $b[1] -gt $bounds[1] + 4 -and $b[3] -lt $bounds[3] - 4 -and
                $b[0] -gt $bounds[0] -and $b[2] -lt $bounds[2] -and
                $b[3] - $b[1] -le ($bounds[3] - $bounds[1]) * .25) {
                $firstCandidate = $true
            }
            if ($i -eq $Messages.Count - 1) { $lastSemantic = $true }
        }
    }
    return [pscustomobject]@{
        bounds = $bounds; semantic_rounds = @($rounds | Sort-Object)
        first_short_node_candidate = $firstCandidate; last_full_text_semantic = $lastSemantic
        sentinel_pixels_confirmed = $false
    }
}

function Invoke-QaLongLeg(
    [ValidateSet('first', 'last')][string]$Endpoint, $Messages,
    [ValidateRange(1, 160)][int]$MaxSwipes,
    [ValidateRange(1, 900)][int]$MaxSeconds,
    [scriptblock]$CaptureFrame, [scriptblock]$ScrollFrame, [scriptblock]$RecordObservation,
    [ValidateRange(1, 300)][int]$NoProgressSeconds = 90,
    [scriptblock]$ElapsedSeconds) {
    $clock = [Diagnostics.Stopwatch]::StartNew()
    if ($null -eq $ElapsedSeconds) { $ElapsedSeconds = { $clock.Elapsed.TotalSeconds } }
    $swipes = 0
    $stable = 0
    $previousPixels = $null
    $previousBounds = ''
    $observed = [Collections.Generic.HashSet[int]]::new()
    $candidate = $null
    $reason = 'swipe_budget_exhausted'
    $lastProgress = 0.0
    $bestRound = $null
    $pixelChanges = 0
    $roundAdvances = 0
    $lastEvidence = $null
    while ($true) {
        if ((& $ElapsedSeconds) -ge $MaxSeconds) { $reason = 'time_budget_exhausted'; break }
        $frame = & $CaptureFrame "$Endpoint-$swipes"
        $lastEvidence = $frame.Evidence
        $observation = Get-QaLongObservation $frame.Document $Messages
        foreach ($round in $observation.semantic_rounds) { $null = $observed.Add($round) }
        $geometry = $observation.bounds -join ','
        $delta = $null
        if ($null -ne $previousPixels -and $geometry -ceq $previousBounds) {
            $delta = Compare-QaChatPixels $previousPixels $frame.Pixels
            $stable = if ($delta -le .01) { $stable + 1 } else { 0 }
        } else { $stable = 0 }
        $previousPixels = $frame.Pixels
        $previousBounds = $geometry
        $elapsed = [double](& $ElapsedSeconds)
        $roundProgress = $false
        if ($observation.semantic_rounds.Count -gt 0) {
            $edge = if ($Endpoint -ceq 'first') { $observation.semantic_rounds[0] }
                else { $observation.semantic_rounds[-1] }
            if ($null -eq $bestRound) { $bestRound = $edge; $lastProgress = $elapsed }
            elseif (($Endpoint -ceq 'first' -and $edge -lt $bestRound) -or
                ($Endpoint -ceq 'last' -and $edge -gt $bestRound)) {
                $bestRound = $edge; $roundProgress = $true; $roundAdvances++
            }
        }
        if ($null -ne $delta -and $delta -gt .01) { $pixelChanges++ }
        if ($roundProgress -or ($null -ne $delta -and $delta -gt .01)) { $lastProgress = $elapsed }
        $row = [ordered]@{
            endpoint = $Endpoint; swipes = $swipes; elapsed_seconds = [Math]::Round($elapsed, 2)
            semantic_rounds = $observation.semantic_rounds; bounds = $observation.bounds
            first_short_node_candidate = $observation.first_short_node_candidate
            last_full_text_semantic = $observation.last_full_text_semantic
            changed_pixel_fraction = $delta; stable_samples = $stable
            best_semantic_round = $bestRound; directional_round_advance = $roundProgress
            no_progress_seconds = [Math]::Round($elapsed - $lastProgress, 2)
            evidence = $frame.Evidence
        }
        & $RecordObservation $row
        if ($elapsed -ge $MaxSeconds) { $reason = 'time_budget_exhausted'; break }
        # XML long text and stable samples only nominate a frame for review. They never PASS.
        if (($Endpoint -ceq 'first' -and $observation.first_short_node_candidate) -or
            ($Endpoint -ceq 'last' -and $observation.last_full_text_semantic -and $stable -ge 3)) {
            $candidate = $frame.Evidence
            $reason = 'endpoint_pixels_require_review'
            break
        }
        if ($swipes -ge $MaxSwipes) { break }
        if ($elapsed - $lastProgress -ge $NoProgressSeconds) { $reason = 'no_pixel_or_directional_round_progress'; break }
        & $ScrollFrame $frame.Document ($Endpoint -ceq 'first') ($stable -ge 3)
        $swipes++
    }
    $clock.Stop()
    return [pscustomobject]@{
        endpoint = $Endpoint; status = if ($null -eq $candidate) { 'INCOMPLETE' } else { 'PENDING_REVIEW' }
        reason = $reason; swipes = $swipes; elapsed_seconds = [Math]::Round([double](& $ElapsedSeconds), 2)
        observed_semantic_rounds = @($observed | Sort-Object); candidate = $candidate
        max_swipes = $MaxSwipes; max_seconds = $MaxSeconds
        no_progress_seconds = $NoProgressSeconds; best_semantic_round = $bestRound
        directional_round_advances = $roundAdvances; pixel_changes = $pixelChanges; last_evidence = $lastEvidence
        semantics_are_not_visible_lines = $true
    }
}

function Get-QaLongCoverageStatus($Legs, [bool]$Failed) {
    if ($Failed -or @($Legs | Where-Object status -eq 'FAIL').Count -gt 0) { return 'FAIL' }
    if (@($Legs).Count -ne 2 -or @($Legs | Where-Object status -eq 'INCOMPLETE').Count -gt 0) { return 'INCOMPLETE' }
    # This runner cannot certify endpoint pixels, even if caller-provided rows say PASS.
    return 'PENDING_REVIEW'
}
