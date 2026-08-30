# Exercise SEC-01 — The TrustDesk boundary map

**After lecture:** *TrustDesk Architecture: Every Boundary on One Map*

## Goal

Draw TrustDesk's architecture **from memory** with its four core boundaries,
then place each of the five threat-model shifts on it. This map is the
foundation for the whole course — every later control lives on a line you
draw here.

## Steps

1. Without looking at the lecture, sketch the TrustDesk map on paper or in
   [boundary-map.md](boundary-map.md) (a template is provided).
2. Mark the **four boundaries**:
   - identity at the front door,
   - the trusted/untrusted channel split inside the chat service,
   - the model's glass wall,
   - the proposal-to-execution gate.
3. Place the **five shifts** from the threat-model lecture onto their exact
   spots on the map.
4. Compare with the lecture and correct anything you missed.

## Done when

- Your map shows all four boundaries with correct placement.
- All five shifts have a concrete address on the map — nothing abstract left.
- You can answer, for any component: *which side of which boundary is it on?*

Keep your finished map next to you for the rest of the course; the SEC-01
assessment builds directly on it.
