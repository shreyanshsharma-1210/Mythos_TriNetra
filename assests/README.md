# Trinetra Android VectorDrawable Assets

These files are real Android VectorDrawable XML resources, not raster images.

Copy the XML files into:

app/src/main/res/drawable/

Then use them in Jetpack Compose:

Image(
    painter = painterResource(R.drawable.trinetra_01_meet),
    contentDescription = null,
    modifier = Modifier.size(280.dp)
)

Important:
- These are intentionally flat/vector-safe.
- They avoid external bitmap dependencies.
- They use Android VectorDrawable-compatible path primitives.
- They can be recolored later by editing fillColor/strokeColor or by building themed variants.
- For the exact soft-3D reference aesthetic, gradients/shadows can be added later with Compose layers or more advanced vector assets.
