param(
    [string]$OutputDir = "app/src/main/assets/memory-model/bge-small-zh-v1.5"
)

$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$ResolvedOutputDir = if ([System.IO.Path]::IsPathRooted($OutputDir)) {
    $OutputDir
} else {
    Join-Path $ProjectRoot $OutputDir
}

function Get-VerifiedArtifact {
    param(
        [string]$Uri,
        [string]$RelativePath,
        [long]$ExpectedSize,
        [string]$ExpectedSha256
    )

    $Target = Join-Path $ResolvedOutputDir $RelativePath
    $Parent = Split-Path -Parent $Target
    New-Item -ItemType Directory -Force -Path $Parent | Out-Null

    if (Test-Path -LiteralPath $Target) {
        $ExistingHash = (Get-FileHash -LiteralPath $Target -Algorithm SHA256).Hash
        $ExistingSize = (Get-Item -LiteralPath $Target).Length
        if ($ExistingSize -eq $ExpectedSize -and $ExistingHash -eq $ExpectedSha256) {
            Write-Host "Verified existing artifact: $RelativePath"
            return
        }
    }

    $Download = "$Target.download"
    Invoke-WebRequest -Uri $Uri -OutFile $Download
    $ActualSize = (Get-Item -LiteralPath $Download).Length
    $ActualHash = (Get-FileHash -LiteralPath $Download -Algorithm SHA256).Hash
    if ($ActualSize -ne $ExpectedSize -or $ActualHash -ne $ExpectedSha256) {
        Remove-Item -LiteralPath $Download -Force
        throw "Artifact verification failed for $RelativePath. Size=$ActualSize SHA256=$ActualHash"
    }
    Move-Item -LiteralPath $Download -Destination $Target -Force
    Write-Host "Provisioned artifact: $RelativePath"
}

$OfficialRevision = "7999e1d3359715c523056ef9478215996d62a620"
$CanaryRevision = "75c43b069aac4d136ba6bc1122f995fedcfd2781"

$Artifacts = @(
    @{
        Uri = "https://huggingface.co/Xenova/bge-small-zh-v1.5/resolve/$CanaryRevision/onnx/model_quantized.onnx"
        Path = "model.onnx"
        Size = 24010842
        Sha256 = "15B717C382BCB518BA457B93EA6850EDE7F4F1CD8937454AA06972366CD19BCC"
    },
    @{
        Uri = "https://huggingface.co/Xenova/bge-small-zh-v1.5/resolve/$CanaryRevision/quantize_config.json"
        Path = "quantize_config.json"
        Size = 674
        Sha256 = "2CC488B20FA05FE86ABA2FDC2BE44D24827E11E2B7C7A0753D1427DA6797B46F"
    },
    @{
        Uri = "https://huggingface.co/BAAI/bge-small-zh-v1.5/resolve/$OfficialRevision/README.md"
        Path = "MODEL_CARD.md"
        Size = 27670
        Sha256 = "C48A4EEEA77F6B1D38B48EC1C5B8D4F86D5550CC43FA345A0DB1B2CA1D082369"
    },
    @{
        Uri = "https://huggingface.co/BAAI/bge-small-zh-v1.5/resolve/$OfficialRevision/config.json"
        Path = "config.json"
        Size = 776
        Sha256 = "3853A7979202C348751B753E36F579C41D8DA7D36AF617D3D907E1FC9B441F2A"
    },
    @{
        Uri = "https://huggingface.co/BAAI/bge-small-zh-v1.5/resolve/$OfficialRevision/tokenizer.json"
        Path = "tokenizer.json"
        Size = 439125
        Sha256 = "48CEA5D44424912A6FD1EA647BF4FE50B55AB8B1E5879C3275F80E339E8FAE26"
    },
    @{
        Uri = "https://huggingface.co/BAAI/bge-small-zh-v1.5/resolve/$OfficialRevision/tokenizer_config.json"
        Path = "tokenizer_config.json"
        Size = 367
        Sha256 = "E6F3B96DB926A37D4039995FBF5AD17DE158DFB8F6343D607E4DBAAD18D75F5A"
    },
    @{
        Uri = "https://huggingface.co/BAAI/bge-small-zh-v1.5/resolve/$OfficialRevision/vocab.txt"
        Path = "vocab.txt"
        Size = 109540
        Sha256 = "45BBAC6B341C319ADC98A532532882E91A9CEFC0329AA57BAC9AE761C27B291C"
    }
)

foreach ($Artifact in $Artifacts) {
    Get-VerifiedArtifact `
        -Uri $Artifact.Uri `
        -RelativePath $Artifact.Path `
        -ExpectedSize $Artifact.Size `
        -ExpectedSha256 $Artifact.Sha256
}

Write-Host "Production memory model artifacts are verified under $ResolvedOutputDir"
Write-Host "Release builds will independently verify every artifact before packaging."
