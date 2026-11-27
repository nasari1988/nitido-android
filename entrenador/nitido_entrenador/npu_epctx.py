"""
(Copia de ci/npu_epctx.py del repo de Nítido.)
Builds a tiny ONNX model with one EPContext node that points to a QNN context binary, so ONNX
Runtime's QNN execution provider loads the precompiled graph directly (nothing compiles on the phone).
usage: npu_epctx.py <ctx.json from qnn-context-binary-utility> <out.onnx> <binary file name> <sdk version>
"""
import json
import sys

import onnx
from onnx import TensorProto, helper

ctx_json, out_path, bin_name, sdk = sys.argv[1:5]
info = json.load(open(ctx_json))

# QNN data type codes -> ONNX
QNN_TO_ONNX = {
    0x0008: TensorProto.INT8, 0x0016: TensorProto.INT16, 0x0032: TensorProto.INT32, 0x0064: TensorProto.INT64,
    0x0108: TensorProto.UINT8, 0x0116: TensorProto.UINT16, 0x0132: TensorProto.UINT32, 0x0164: TensorProto.UINT64,
    0x0216: TensorProto.FLOAT16, 0x0232: TensorProto.FLOAT, 0x0508: TensorProto.BOOL,
    # quantized integers: ONNX Runtime for Java can't make uint16 tensors, so they are declared
    # int16 (same 2 bytes; the app writes the unsigned value) and uint8 as is
    0x0416: TensorProto.INT16, 0x0408: TensorProto.UINT8, 0x0316: TensorProto.INT16, 0x0308: TensorProto.INT8,
}


def find_scale_offset(t):
    """Scale/offset of a quantized tensor, wherever this SDK puts them in the JSON."""
    if isinstance(t, dict):
        keys = {k.lower(): k for k in t}
        if "scale" in keys and "offset" in keys:
            try:
                return float(t[keys["scale"]]), int(t[keys["offset"]])
            except (TypeError, ValueError):
                pass
        for v in t.values():
            r = find_scale_offset(v)
            if r:
                return r
    elif isinstance(t, list):
        for v in t:
            r = find_scale_offset(v)
            if r:
                return r
    return None


def find_graphs(d):
    """The JSON layout changed across SDK versions: look for objects that have graph inputs."""
    found = []
    if isinstance(d, dict):
        keys = {k.lower(): k for k in d}
        if "graphinputs" in keys and "graphname" in keys:
            found.append(d)
        for v in d.values():
            found += find_graphs(v)
    elif isinstance(d, list):
        for v in d:
            found += find_graphs(v)
    return found


def tensor_info(t):
    raw = t
    t = t.get("info", t)
    keys = {k.lower(): k for k in t}
    name = t[keys["name"]]
    dt = t[keys.get("datatype", "dataType")] if ("datatype" in keys) else t.get("dataType")
    if isinstance(dt, str):
        dt = int(dt, 0) if dt.lower().startswith("0x") else {
            "QNN_DATATYPE_FLOAT_16": 0x0216, "QNN_DATATYPE_FLOAT_32": 0x0232, "QNN_DATATYPE_INT_32": 0x0032,
            "QNN_DATATYPE_UINT_32": 0x0132, "QNN_DATATYPE_INT_64": 0x0064,
            "QNN_DATATYPE_UFIXED_POINT_16": 0x0416, "QNN_DATATYPE_UFIXED_POINT_8": 0x0408,
            "QNN_DATATYPE_SFIXED_POINT_16": 0x0316, "QNN_DATATYPE_SFIXED_POINT_8": 0x0308}.get(dt, 0x0232)
    dims = t[keys["dimensions"]]
    so = find_scale_offset(raw) if int(dt) in (0x0416, 0x0408, 0x0316, 0x0308) else None
    info = (name, QNN_TO_ONNX.get(int(dt), TensorProto.FLOAT), [int(x) for x in dims])
    QUANT[name] = {"qnn_type": int(dt), "scale": so[0], "offset": so[1]} if so else {"qnn_type": int(dt)}
    return info


QUANT = {}


graphs = find_graphs(info)
if not graphs:
    sys.exit("no se encontró el grafo en " + ctx_json)
g = graphs[0]
gk = {k.lower(): k for k in g}
gname = g[gk["graphname"]]
ins = [tensor_info(t) for t in g[gk["graphinputs"]]]
outs = [tensor_info(t) for t in g[gk["graphoutputs"]]]
print("grafo:", gname)
print("entradas:", ins)
print("salidas:", outs)

node = helper.make_node(
    "EPContext", [n for n, _, _ in ins], [n for n, _, _ in outs], name=gname, domain="com.microsoft",
    embed_mode=0, ep_cache_context=bin_name, source="Qnn", main_context=1, partition_name=gname, ep_sdk_version=sdk,
)
graph = helper.make_graph(
    [node], "unet_npu",
    [helper.make_tensor_value_info(n, t, d) for n, t, d in ins],
    [helper.make_tensor_value_info(n, t, d) for n, t, d in outs],
)
model = helper.make_model(graph, opset_imports=[helper.make_opsetid("", 17), helper.make_opsetid("com.microsoft", 1)])
onnx.save(model, out_path)
# the app reads the real tensor names and types from here
with open(out_path + ".json", "w") as fh:
    json.dump({"graph": gname, "inputs": ins, "outputs": outs, "quant": QUANT}, fh)
print("cuantización:", QUANT)
print("envoltura:", out_path)
