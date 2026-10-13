param([string]$OutputPath)
$ErrorActionPreference = 'Stop'
$repository = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
if (-not $OutputPath) {
    $OutputPath = Join-Path $repository '../Temp_Android/qa/palette-1.0.2-render/uniforms.json'
}
$records = @()
foreach ($name in @('GlassSurface.kt', 'GlassPairLensShader.kt')) {
    $path = Join-Path $repository ('app/src/main/java/com/dafeiyu/mobile/ui/components/' + $name)
    $text = [System.IO.File]::ReadAllText($path)
    $source = [regex]::Match($text, 'private const val SOURCE = """([\s\S]*?)"""').Groups[1].Value
    if (-not $source) { throw "Missing shader SOURCE: $name" }
    $uniforms = @([regex]::Matches($source, 'uniform\s+(?!shader\b)\w+\s+(\w+)\s*;') |
        ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique)
    $setters = @([regex]::Matches($text, 'setFloatUniform\("([^"]+)"') | ForEach-Object {
        $key = $_.Groups[1].Value
        if ($key.Contains('$suffix')) { $key.Replace('$suffix', '1'); $key.Replace('$suffix', '2') } else { $key }
    } | Sort-Object -Unique)
    $body = [regex]::Replace($source, 'uniform\s+\w+\s+\w+\s*;', '')
    $missingSetters = @($uniforms | Where-Object { $_ -notin $setters })
    $extraSetters = @($setters | Where-Object { $_ -notin $uniforms })
    $unusedUniforms = @($uniforms | Where-Object { -not [regex]::IsMatch($body, '\b' + [regex]::Escape($_) + '\b') })
    $reservedNames = @([regex]::Matches($body, '\b(?:float|float2|float3|float4|half|half4)\s+(sample|distance)\b') |
        ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique)
    $records += [ordered]@{ file = $name; sha256 = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant();
        uniforms = $uniforms; count = $uniforms.Count; missingSetters = $missingSetters;
        extraSetters = $extraSetters; unusedUniforms = $unusedUniforms; reservedNames = $reservedNames }
}
$problems = @($records | Where-Object {
    $_.missingSetters.Count -gt 0 -or $_.extraSetters.Count -gt 0 -or $_.unusedUniforms.Count -gt 0 -or $_.reservedNames.Count -gt 0
})
$report = [ordered]@{ kind = 'static-shader-uniform-audit'; runtimeCompiled = $false;
    shaders = $records; problemShaders = $problems.Count }
$output = [System.IO.Path]::GetFullPath($OutputPath)
[System.IO.Directory]::CreateDirectory([System.IO.Path]::GetDirectoryName($output)) | Out-Null
[System.IO.File]::WriteAllText($output, ($report | ConvertTo-Json -Depth 8), [System.Text.UTF8Encoding]::new($false))
if ($problems.Count -gt 0) { throw "Shader uniform audit failed: $($problems.Count) shader(s). See $output" }
Write-Output "Static shader audit: $((($records | ForEach-Object { $_.count }) | Measure-Object -Sum).Sum) uniforms, 0 problems. $output"
