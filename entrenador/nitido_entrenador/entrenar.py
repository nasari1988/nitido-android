"""
El entrenamiento: datos con aumentos al vuelo, la vuelta, la medida «parecido a tu edición»,
ejemplos en cada vuelta y una copia guardada en cada vuelta (si se apaga la PC, sigue desde ahí).
"""
import json
import math
import os
import random
import subprocess
import threading
import time

import numpy as np
import torch
import torch.nn.functional as F
from PIL import Image
from torch.utils.data import DataLoader, Dataset

from . import datos
from .modelos import MiEstilo, MiPiel, pesos

CONFIG = {
    "estilo": {"lado": 384, "lote": 8, "vueltas": 40, "lr": 5e-4, "max_lado": 1024},
    "piel": {"lado": 512, "lote": 4, "vueltas": 60, "lr": 2e-4, "max_lado": 2048},
}


def a_tensor(im):
    return torch.from_numpy(np.asarray(im, dtype=np.float32) / 255.0).permute(2, 0, 1)


def a_imagen(t):
    return Image.fromarray((t.detach().float().clamp(0, 1).permute(1, 2, 0).cpu().numpy() * 255 + 0.5).astype(np.uint8))


def _par(p, max_lado):
    a = datos.cargado(p["antes"], max_lado)
    d = datos.cargado(p["despues"], max_lado)
    if d.size != a.size:
        d = d.resize(a.size, Image.LANCZOS)
    return a, d


class DatosEstilo(Dataset):
    def __init__(self, pares, cfg, aum, variantes):
        self.p, self.cfg, self.aum, self.n = pares, cfg, aum, variantes

    def __len__(self):
        return len(self.p) * self.n

    def __getitem__(self, i):
        a, d = _par(self.p[i % len(self.p)], self.cfg["max_lado"])
        a, d = datos.aumentar(a, d, self.aum, self.cfg["lado"])
        return a_tensor(a), a_tensor(d), torch.ones(1, self.cfg["lado"], self.cfg["lado"])


class DatosPiel(Dataset):
    """Piezas de 512 px donde hay piel; la pérdida pesa más en la piel (la máscara)."""

    def __init__(self, pares, cajas, cache, cfg, aum, variantes):
        self.p, self.c, self.cache, self.cfg, self.n = pares, cajas, cache, cfg, variantes
        # en la piel no se cambia el color del «antes» (el retoque no es de color): solo encuadre y ruido
        self.aum = dict(aum, exposicion=min(aum["exposicion"], 0.2), temperatura=min(aum["temperatura"], 200),
                        contraste=min(aum["contraste"], 0.05), saturacion=min(aum["saturacion"], 0.05),
                        recorte_min=0.85, zoom=[0.9, 1.1])

    def __len__(self):
        return len(self.c) * self.n

    def __getitem__(self, i):
        ip, x, y = self.c[i % len(self.c)]
        p = self.p[ip]
        a, d = _par(p, self.cfg["max_lado"])
        L = self.cfg["lado"]
        m = Image.open(os.path.join(self.cache, f"piel_{p['nombre']}.png")).convert("L")
        if m.size != a.size:
            m = m.resize(a.size, Image.BILINEAR)
        # la pieza, un poco más grande para que los aumentos no dejen bordes
        e = L // 8
        box = (max(0, x - e), max(0, y - e), min(a.width, x + L + e), min(a.height, y + L + e))
        a, d, m = a.crop(box), d.crop(box), m.crop(box)
        s = random.getstate()
        mm = Image.merge("RGB", (m, m, m))
        a2, d2 = datos.aumentar(a, d, self.aum, L)
        random.setstate(s)
        _, m2 = datos.aumentar(a, mm, dict(self.aum, exposicion=0, temperatura=0, contraste=0, saturacion=0, ruido=False, jpeg=False), L)
        w = a_tensor(m2)[:1] * 0.85 + 0.15
        return a_tensor(a2), a_tensor(d2), w


def _gpu():
    try:
        r = subprocess.run(["nvidia-smi", "--query-gpu=temperature.gpu,memory.used,memory.total", "--format=csv,noheader,nounits"],
                           capture_output=True, text=True, timeout=3)
        t, u, tot = [int(v) for v in r.stdout.strip().split(",")[:3]]
        return {"temp": t, "vram": round(u / 1024, 1), "vram_total": round(tot / 1024, 1)}
    except Exception:
        return {}


class Entrenamiento:
    """Una corrida en segundo plano; [estado] lo lee la página."""

    def __init__(self, proyecto, modelo):
        self.dir = proyecto
        self.modelo = modelo
        self.pausa = threading.Event()
        self.alto = threading.Event()
        self.estado = {"modelo": modelo, "fase": "preparando", "vuelta": 0, "vueltas": 0, "paso": 0, "pasos": 0,
                       "falta_s": None, "perdida": [], "prueba": [], "parecido": None, "mejor": None, "mensaje": "", "gpu": {}}
        self.hilo = threading.Thread(target=self._correr, daemon=True)

    def iniciar(self):
        self.hilo.start()

    def _msg(self, t):
        self.estado["mensaje"] = t
        print(t, flush=True)

    def _correr(self):
        try:
            self._entrenar()
        except Exception as e:
            import traceback
            traceback.print_exc()
            self.estado["fase"] = "error"
            self._msg(f"Error: {e}")

    def _entrenar(self):
        P = json.load(open(os.path.join(self.dir, "proyecto.json")))
        cfg = dict(CONFIG[self.modelo], **P.get("config", {}).get(self.modelo, {}))
        aum = dict(datos.AUMENTOS_BASE, **P.get("aumentos", {}))
        pares = [p for p in P["pares"] if p.get("usar", True)]
        if len(pares) < 4:
            raise RuntimeError("Hacen falta al menos 4 pares")
        rnd = random.Random(7)
        orden = pares[:]; rnd.shuffle(orden)
        nprueba = max(2, len(orden) // 10)
        prueba, entreno = orden[:nprueba], orden[nprueba:]
        dev = "cuda" if torch.cuda.is_available() else "cpu"
        self._msg(f"{len(entreno)} pares para aprender y {len(prueba)} apartados para medir · {dev}")
        cache = os.path.join(self.dir, "cache")
        if self.modelo == "piel":
            self.estado["fase"] = "buscando piel"
            piel = datos.Piel()
            c_ent = datos.recortes_piel(entreno, cache, cfg["lado"], piel, cfg["max_lado"])
            c_pru = datos.recortes_piel(prueba, cache, cfg["lado"], piel, cfg["max_lado"])
            if not c_ent:
                raise RuntimeError("No encontré piel en tus fotos")
            self._msg(f"{len(c_ent)} piezas de piel para aprender · {len(c_pru)} para medir")
            ds = DatosPiel(entreno, c_ent, cache, cfg, aum, aum["variantes"])
            dsp = DatosPiel(prueba, c_pru[:60], cache, cfg, dict(aum, variantes=1), 1)
            red = MiPiel()
        else:
            ds = DatosEstilo(entreno, cfg, aum, aum["variantes"])
            dsp = DatosEstilo(prueba, cfg, dict(aum, exposicion=0, temperatura=0, contraste=0, saturacion=0, ruido=False), 3)
            red = MiEstilo()
        self._msg(f"Modelo: {pesos(red) / 1e6:.1f} millones de pesos · piezas de {cfg['lado']} px · lote {cfg['lote']}")
        red.to(dev)
        hilos = min(6, os.cpu_count() or 2)
        dl = DataLoader(ds, batch_size=cfg["lote"], shuffle=True, num_workers=hilos, drop_last=True, persistent_workers=hilos > 0)
        dlp = DataLoader(dsp, batch_size=cfg["lote"], shuffle=False, num_workers=min(2, hilos))
        opt = torch.optim.AdamW(red.parameters(), lr=cfg["lr"], weight_decay=1e-4)
        total = cfg["vueltas"] * len(dl)
        sched = torch.optim.lr_scheduler.OneCycleLR(opt, max_lr=cfg["lr"], total_steps=total, pct_start=0.05)
        amp = dev == "cuda"
        scaler = torch.amp.GradScaler("cuda", enabled=amp)
        ck = os.path.join(self.dir, f"{self.modelo}_ultimo.pt")
        v0 = 0
        if os.path.exists(ck):
            s = torch.load(ck, map_location=dev, weights_only=True)
            red.load_state_dict(s["red"]); opt.load_state_dict(s["opt"]); sched.load_state_dict(s["sched"])
            v0 = int(s["vuelta"]); self.estado["perdida"] = s.get("perdida", []); self.estado["prueba"] = s.get("prueba", [])
            self.estado["mejor"] = s.get("mejor")
            self._msg(f"Sigo desde la vuelta {v0}")
        self.estado.update(fase="entrenando", vueltas=cfg["vueltas"], pasos=len(dl))
        os.makedirs(os.path.join(self.dir, "muestras"), exist_ok=True)
        t0 = time.time(); hechos = 0
        for v in range(v0, cfg["vueltas"]):
            red.train()
            suma, n = 0.0, 0
            for k, (a, d, w) in enumerate(dl):
                while self.pausa.is_set() and not self.alto.is_set():
                    self.estado["fase"] = "en pausa"; time.sleep(0.5)
                if self.alto.is_set():
                    break
                self.estado["fase"] = "entrenando"
                a, d, w = a.to(dev, non_blocking=True), d.to(dev, non_blocking=True), w.to(dev, non_blocking=True)
                with torch.autocast(device_type="cuda", dtype=torch.float16, enabled=amp):
                    y = red(a)
                    loss = (torch.abs(y.float() - d) * w).mean() / w.mean()
                    if self.modelo == "piel":
                        # la textura: las orillas finas del resultado se parecen a las de tu retoque
                        gy = lambda t: t[..., 1:, :] - t[..., :-1, :]
                        gx = lambda t: t[..., :, 1:] - t[..., :, :-1]
                        loss = loss + 0.5 * (torch.abs(gx(y.float()) - gx(d)).mean() + torch.abs(gy(y.float()) - gy(d)).mean())
                if self.modelo == "estilo":
                    suave, orden_ = red.regulares()
                    loss = loss + 1e-4 * suave + 10 * orden_
                opt.zero_grad(set_to_none=True)
                scaler.scale(loss).backward()
                scaler.step(opt); scaler.update(); sched.step()
                suma += loss.item(); n += 1; hechos += 1
                self.estado["paso"] = k + 1
                if hechos % 20 == 0:
                    rate = (time.time() - t0) / hechos
                    self.estado["falta_s"] = int(rate * (total - v * len(dl) - k - 1))
                    self.estado["gpu"] = _gpu()
            if self.alto.is_set():
                break
            self.estado["vuelta"] = v + 1
            self.estado["perdida"].append(round(suma / max(1, n), 5))
            par = self._medir(red, dlp, dev, amp, v + 1)
            self.estado["prueba"].append(par["error"])
            self.estado["parecido"] = par["parecido"]
            mejor = self.estado["mejor"]
            if mejor is None or par["error"] <= mejor["error"]:
                self.estado["mejor"] = {"vuelta": v + 1, "error": par["error"], "parecido": par["parecido"]}
                torch.save({"red": red.state_dict(), "modelo": self.modelo, "vuelta": v + 1, "parecido": par["parecido"]},
                           os.path.join(self.dir, f"{self.modelo}_mejor.pt"))
            torch.save({"red": red.state_dict(), "opt": opt.state_dict(), "sched": sched.state_dict(), "vuelta": v + 1,
                        "perdida": self.estado["perdida"], "prueba": self.estado["prueba"], "mejor": self.estado["mejor"]}, ck)
            self._msg(f"Vuelta {v + 1}/{cfg['vueltas']} · error {self.estado['perdida'][-1]:.4f} · parecido a tu edición {par['parecido']} %")
        self.estado["fase"] = "detenido" if self.alto.is_set() else "terminado"
        self._msg("Guardado. " + ("Detenido." if self.alto.is_set() else "Terminado: ya puedes probar y exportar."))

    @torch.no_grad()
    def _medir(self, red, dlp, dev, amp, vuelta):
        """Qué tanto del cambio de tu edición aprendió (100 % = igual a tu edición) y unos ejemplos."""
        red.eval()
        em = ei = 0.0
        ejemplos = []
        for a, d, w in dlp:
            a, d, w = a.to(dev), d.to(dev), w.to(dev)
            with torch.autocast(device_type="cuda", dtype=torch.float16, enabled=amp):
                y = red(a).float()
            em += float((torch.abs(y - d) * w).sum() / w.sum()) * len(a)
            ei += float((torch.abs(a - d) * w).sum() / w.sum()) * len(a)
            for j in range(len(a)):
                if len(ejemplos) < 3:
                    ejemplos.append((a[j], y[j], d[j]))
        n = max(1, len(dlp.dataset))
        em, ei = em / n, ei / n
        if ejemplos:
            L = ejemplos[0][0].shape[-1]
            hoja = Image.new("RGB", (3 * 256 + 20, len(ejemplos) * 256 + 10 * (len(ejemplos) - 1)), (10, 13, 11))
            for r, trio in enumerate(ejemplos):
                for c, t in enumerate(trio):
                    hoja.paste(a_imagen(t).resize((256, 256), Image.LANCZOS), (c * 266, r * 266))
            hoja.save(os.path.join(self.dir, "muestras", f"vuelta_{vuelta:03d}.jpg"), quality=88)
        red.train()
        return {"error": round(em, 5), "parecido": round(max(0.0, 1 - em / max(ei, 1e-6)) * 100, 1)}
