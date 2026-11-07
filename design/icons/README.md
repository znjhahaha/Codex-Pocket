# Pocket Outline

Original in-app line icons shared by the Android and Windows clients.

- 24-unit grid, 1.65-unit stroke, round caps and joins.
- The normal display size is 20–24 dp; action targets remain at least 48 dp on Android.
- Icons inherit text color. Selected navigation uses a restrained tinted surface and text label.
- Back arrows mirror in right-to-left layouts. Status and destructive actions retain readable labels.
- `scripts/build-ui-icons.py` generates Compose ImageVectors, React SVG components and the preview from one geometry definition. No icon fonts or remote assets are required.

`preview.png` shows navigation, composer and menu artwork in light/dark surfaces and small sizes.
The launcher icon is a separate asset; this family replaces the in-app Material/Lucide defaults.
