"""Replays a navigation-sim run inside Webots.

Generated next to trace.json by navigation-sim (WebotsExporter). Webots is only the viewer: the
engine, depth and the user all ran in the simulator; this moves the user along the recording,
shows what they were being told, leaves a trail coloured by instruction, and draws the engine's
occupancy map on the floor (green = believed free, red = believed occupied) as it grew.

Plays in simulation time: use Webots' play / fast-forward / pause buttons. Reset the world to
start again.
"""
import json
import math
import os
import traceback

from controller import Supervisor

COLOURS = {
    "STRAIGHT": (0.35, 0.82, 0.47),
    "TURN_LEFT": (0.35, 0.63, 0.94),
    "TURN_RIGHT": (0.35, 0.63, 0.94),
    "STOP": (0.9, 0.31, 0.27),
    "SCAN": (0.94, 0.78, 0.35),
    "ARRIVED": (1.0, 1.0, 1.0),
}
CRUMB_PERIOD = 0.5
LOG = os.path.join(os.path.dirname(os.path.abspath(__file__)), "replay.log")


def log(message):
    """Webots does not always surface controller output, so progress also goes to replay.log."""
    print(message)
    with open(LOG, "a", encoding="utf-8") as f:
        f.write(message + "\n")


def axis_angle(yaw, pitch):
    """Axis-angle for a rotation of `yaw` about z, then `pitch` (down) about the new y."""
    cy, sy = math.cos(yaw / 2), math.sin(yaw / 2)
    cp, sp = math.cos(pitch / 2), math.sin(pitch / 2)
    w, x, y, z = cy * cp, -sy * sp, cy * sp, sy * cp
    angle = 2 * math.acos(max(-1.0, min(1.0, w)))
    s = math.sqrt(max(1e-12, 1 - w * w))
    return [x / s, y / s, z / s, angle]


def grid_node(grid):
    """The engine's map as two flat meshes, merging runs of equal cells along each row."""
    n, res = grid["n"], grid["res"]
    cells = grid["cells"]
    yaw = grid["yaw"]
    rx, ry = math.cos(yaw), -math.sin(yaw)  # canonical +x (right) in plan
    fx, fy = math.sin(yaw), math.cos(yaw)   # canonical +z (forward) in plan

    def plan(cx, cz):
        return (grid["ox"] + rx * cx + fx * cz, grid["oy"] + ry * cx + fy * cz)

    meshes = {".": ([], []), "#": ([], [])}
    for gz in range(n):
        row = cells[gz * n:(gz + 1) * n]
        gx = 0
        while gx < n:
            state = row[gx]
            end = gx
            while end + 1 < n and row[end + 1] == state:
                end += 1
            if state in meshes:
                points, faces = meshes[state]
                x0 = grid["x0"] + gx * res
                x1 = grid["x0"] + (end + 1) * res
                z0 = grid["z0"] + gz * res
                z1 = z0 + res
                base = len(points)
                for cx, cz in ((x0, z0), (x1, z0), (x1, z1), (x0, z1)):
                    points.append(plan(cx, cz))
                faces.append(f"{base} {base + 1} {base + 2} {base + 3} -1")
            gx = end + 1

    shapes = []
    for state, (r, g, b, height) in ((".", (0.25, 0.75, 0.4, 0.012)), ("#", (0.9, 0.25, 0.2, 0.02))):
        points, faces = meshes[state]
        if not points:
            continue
        coords = ", ".join(f"{x:.3f} {y:.3f} {height}" for x, y in points)
        shapes.append(
            "Shape { appearance PBRAppearance { baseColor %s %s %s transparency 0.35 roughness 1 metalness 0 } "
            "geometry IndexedFaceSet { solid FALSE coord Coordinate { point [ %s ] } coordIndex [ %s ] } }"
            % (r, g, b, coords, " ".join(faces))
        )
    return 'Solid { name "engine map" children [ %s ] }' % " ".join(shapes)


def main():
    sup = Supervisor()
    step = int(sup.getBasicTimeStep())
    here = os.path.dirname(os.path.abspath(__file__))
    with open(os.path.join(here, "trace.json"), encoding="utf-8") as f:
        trace = json.load(f)
    frames, grids = trace["frames"], trace["grids"]

    user = sup.getSelf()
    translation = user.getField("translation")
    rotation = user.getField("rotation")
    phone = sup.getFromDef("PHONE").getField("rotation")
    root = sup.getRoot().getField("children")

    sup.setLabel(1, trace["title"], 0.01, 0.94, 0.07, 0xFFFFFF, 0, "Arial")
    fi, gi, crumbs, last_crumb, map_node, finished = 0, 0, 0, -1.0, None, False
    log(f"replay started: {trace['title']} ({len(frames)} frames, {len(grids)} map snapshots)")
    next_report = 10.0

    while sup.step(step) != -1:
        t = sup.getTime()
        while fi + 1 < len(frames) and frames[fi + 1][0] <= t:
            fi += 1
        seconds, x, y, heading, phone_yaw, pitch, command, reason, status, touching = frames[fi]

        translation.setSFVec3f([x, y, 0])
        rotation.setSFRotation([0, 0, 1, math.pi / 2 - heading])
        phone.setSFRotation(axis_angle(-(phone_yaw - heading), pitch))

        said = command + (f" ({reason})" if reason else "")
        colour = 0xFF4040 if touching else 0xFFFFFF
        sup.setLabel(0, f"{seconds:6.1f} s   {status}   {said}", 0.01, 0.01, 0.1, colour, 0, "Arial")

        if seconds - last_crumb >= CRUMB_PERIOD:
            last_crumb = seconds
            r, g, b = COLOURS.get(command, (0.7, 0.7, 0.7))
            root.importMFNodeFromString(
                -1,
                'Solid { translation %.3f %.3f 0.03 name "crumb %d" children [ Shape { appearance PBRAppearance '
                "{ baseColor %s %s %s roughness 1 metalness 0 } geometry Cylinder { height 0.01 radius 0.06 } } ] }"
                % (x, y, crumbs, r, g, b),
            )
            crumbs += 1

        while gi < len(grids) and grids[gi]["t"] <= t:
            if map_node is not None:
                map_node.remove()
            root.importMFNodeFromString(-1, grid_node(grids[gi]))
            map_node = root.getMFNode(-1)
            gi += 1

        if t >= next_report and not finished:
            next_report += 10.0
            log(f"t={t:.0f} s  {status} {said}  crumbs={crumbs} map snapshots shown={gi}")

        if fi == len(frames) - 1 and not finished:
            finished = True
            log("replay finished: " + trace["summary"])
            sup.setLabel(2, "END: " + trace["summary"], 0.01, 0.06, 0.06, 0xFFD060, 0, "Arial")
            if trace["failures"]:
                sup.setLabel(3, "FAILED: " + trace["failures"], 0.01, 0.10, 0.06, 0xFF6060, 0, "Arial")


open(LOG, "w").close()
try:
    main()
except Exception:
    log(traceback.format_exc())
    raise
