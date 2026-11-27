"""
Nítido · Entrenador — app de escritorio (Qt). Todo corre en esta PC; no abre ningún puerto.
    python -m nitido_entrenador.app
"""
import os
import sys
import traceback

from PySide6.QtCore import QObject, QRunnable, Qt, QThreadPool, QTimer, Signal, QPointF
from PySide6.QtGui import QColor, QFont, QIcon, QImage, QPainter, QPen, QPixmap, QPolygonF
from PySide6.QtWidgets import (QApplication, QButtonGroup, QCheckBox, QDoubleSpinBox, QFileDialog,
                               QFrame, QGridLayout, QHBoxLayout, QLabel, QLineEdit, QMainWindow, QMessageBox, QProgressBar, QPushButton, QScrollArea, QSizePolicy, QSlider,
                               QSpinBox, QStackedWidget, QVBoxLayout, QWidget)

from . import datos, nucleo

# ---------------------------------------------------------------- colores (los mismos de la app del teléfono)
BG, CARD, LINE, FG, MUTED, ACC, WARN, BAD = "#0A0D0B", "#141A16", "#2B3A31", "#EDE6D6", "#A8A08E", "#8DBA8A", "#E8C27A", "#E9A99E"

ESTILO = f"""
* {{ font-size: 14px; }}
QMainWindow, QWidget#fondo {{ background: {BG}; }}
QWidget {{ color: {FG}; }}
QLabel {{ background: transparent; }}
QWidget#lado {{ background: {BG}; border-right: 1px solid #1F2923; }}
QLabel#titulo {{ font-size: 17px; font-weight: 600; padding: 0 6px 8px 6px; }}
QLabel#h1 {{ font-size: 22px; font-weight: 600; }}
QLabel[muted="true"] {{ color: {MUTED}; font-size: 12px; }}
QLabel[err="true"] {{ color: {BAD}; }}
QFrame[caja="true"] {{ background: {CARD}; border: 1px solid {LINE}; border-radius: 12px; }}
QFrame[caja="aviso"] {{ background: {CARD}; border: 1px solid #5A4220; border-radius: 12px; }}
QFrame[caja="acento"] {{ background: {CARD}; border: 1px solid {ACC}; border-radius: 12px; }}
QPushButton {{ font-weight: 600; }}
QPushButton[tipo="tab"] {{ height: 32px; padding: 0 14px; border-radius: 16px; border: 1px solid #3A4A3F; background: transparent; color: #C9C1AE; font-weight: 500; }}
QPushButton[tipo="tab"]:checked {{ background: {ACC}; color: {BG}; border-color: {ACC}; font-weight: 600; }}
QPushButton[tipo="paso"] {{ text-align: left; height: 44px; padding: 0 12px; border-radius: 10px; border: none; background: transparent; color: #C9C1AE; font-weight: 500; }}
QPushButton[tipo="paso"]:checked {{ background: #18241C; color: {FG}; font-weight: 600; }}
QPushButton[tipo="paso"]:hover:!checked {{ background: #121915; }}
QPushButton[tipo="main"] {{ height: 44px; padding: 0 22px; border-radius: 12px; border: none; background: {FG}; color: #1F3A26; }}
QPushButton[tipo="main"]:disabled {{ background: #3A4A3F; color: #6F7A70; }}
QPushButton[tipo="ghost"] {{ height: 36px; padding: 0 14px; border-radius: 10px; border: 1px solid #3A4A3F; background: transparent; }}
QPushButton[tipo="ghost"]:hover {{ background: #18241C; }}
QPushButton[tipo="peligro"] {{ height: 36px; padding: 0 14px; border-radius: 10px; border: 1px solid #5A3A34; background: transparent; color: {BAD}; }}
QLineEdit, QSpinBox, QDoubleSpinBox {{  background: #0F1411; border: 1px solid #3A4A3F; border-radius: 8px; padding: 6px 8px; color: {FG}; selection-background-color: {ACC}; }}
QAbstractSpinBox::up-button, QAbstractSpinBox::down-button {{ width: 0; border: none; }}
QAbstractSpinBox::up-arrow, QAbstractSpinBox::down-arrow {{ image: none; width: 0; }}
QCheckBox {{ spacing: 8px; }}
QCheckBox::indicator {{ width: 18px; height: 18px; border-radius: 5px; border: 1px solid #3A4A3F; background: #0F1411; }}
QCheckBox::indicator:checked {{ background: {ACC}; border-color: {ACC}; }}
QProgressBar {{ height: 8px; border: none; border-radius: 4px; background: {LINE}; }}
QProgressBar::chunk {{ border-radius: 4px; background: {ACC}; }}
QSlider::groove:horizontal {{ height: 6px; border-radius: 3px; background: {LINE}; }}
QSlider::sub-page:horizontal {{ border-radius: 3px; background: {ACC}; }}
QSlider::handle:horizontal {{ width: 18px; margin: -6px 0; border-radius: 9px; background: {FG}; }}
QScrollArea {{ border: none; background: transparent; }}
QScrollArea > QWidget > QWidget {{ background: transparent; }}
QScrollBar:vertical {{ width: 10px; background: transparent; }}
QScrollBar::handle:vertical {{ background: #2B3A31; border-radius: 5px; min-height: 30px; }}
QScrollBar::add-line, QScrollBar::sub-line {{ height: 0; }}
QToolTip {{ background: {CARD}; color: {FG}; border: 1px solid {LINE}; }}
QMessageBox {{ background: {CARD}; }}
"""


# ---------------------------------------------------------------- ayudas
def a_pixmap(im):
    """PIL -> QPixmap (en el hilo principal)."""
    return QPixmap.fromImage(a_qimage(im))


def a_qimage(im):
    """PIL -> QImage (se puede hacer en un hilo de fondo)."""
    im = im.convert("RGB")
    return QImage(im.tobytes("raw", "RGB"), im.width, im.height, im.width * 3, QImage.Format_RGB888).copy()


def etiqueta(texto="", muted=False, err=False, wrap=True):
    lb = QLabel(texto)
    lb.setWordWrap(wrap)
    lb.setTextInteractionFlags(Qt.TextSelectableByMouse)
    if muted:
        lb.setProperty("muted", True)
    if err:
        lb.setProperty("err", True)
    return lb


def boton(texto, tipo="ghost"):
    b = QPushButton(texto)
    b.setProperty("tipo", tipo)
    b.setCursor(Qt.PointingHandCursor)
    return b


def caja(tipo="true"):
    f = QFrame()
    f.setProperty("caja", tipo)
    lay = QVBoxLayout(f)
    lay.setContentsMargins(14, 12, 14, 12)
    lay.setSpacing(8)
    return f, lay


def repintar(w):
    w.style().unpolish(w); w.style().polish(w)


def limpiar(lay):
    while lay.count():
        it = lay.takeAt(0)
        if it.widget():
            it.widget().deleteLater()
        elif it.layout():
            limpiar(it.layout())


class _Senales(QObject):
    listo = Signal(object)
    fallo = Signal(str)
    avance = Signal(object)


class Tarea(QRunnable):
    """Corre fn(avance) en segundo plano; avisa con listo(resultado) o fallo(mensaje)."""

    def __init__(self, fn, listo=None, fallo=None, avance=None):
        super().__init__()
        self.fn, self.s = fn, _Senales()
        if listo:
            self.s.listo.connect(listo)
        if fallo:
            self.s.fallo.connect(fallo)
        if avance:
            self.s.avance.connect(avance)

    def run(self):
        try:
            try:
                r = self.fn(self.s.avance.emit)
            except nucleo.Aviso as e:
                self.s.fallo.emit(str(e)); return
            except RuntimeError as e:
                if "deleted" in str(e):      # la ventana ya se cerró
                    return
                raise
            except Exception as e:  # noqa: BLE001
                traceback.print_exc()
                self.s.fallo.emit(str(e) or e.__class__.__name__); return
            self.s.listo.emit(r)
        except RuntimeError:                 # la ventana se cerró mientras trabajaba: nadie espera el resultado
            pass


_vivas = set()     # las tareas en curso (si no, Python las borra antes de que avisen)


def en_fondo(fn, listo=None, fallo=None, avance=None):
    t = Tarea(fn, listo, fallo, avance)
    t.setAutoDelete(False)
    _vivas.add(t)
    t.s.listo.connect(lambda *_: _vivas.discard(t))
    t.s.fallo.connect(lambda *_: _vivas.discard(t))
    QThreadPool.globalInstance().start(t)
    return t


def elegir_carpeta(padre, campo):
    d = QFileDialog.getExistingDirectory(padre, "Elige la carpeta", campo.text() or os.path.expanduser("~"))
    if d:
        campo.setText(d)


class Imagen(QLabel):
    """Una foto que se ajusta al ancho disponible sin deformarse."""

    def __init__(self, pix=None, alto_max=None):
        super().__init__()
        self._pix, self._alto_max = None, alto_max
        self.setSizePolicy(QSizePolicy.Ignored, QSizePolicy.Fixed)
        self.setMinimumWidth(60)
        if pix is not None:
            self.poner(pix)

    def poner(self, pix):
        self._pix = pix
        self._ajustar()

    def resizeEvent(self, e):
        super().resizeEvent(e)
        self._ajustar()

    def _ajustar(self):
        if not self._pix or self._pix.isNull():
            return
        w = max(50, self.width())
        h = int(w * self._pix.height() / self._pix.width())
        if self._alto_max and h > self._alto_max:
            h = self._alto_max; w = int(h * self._pix.width() / self._pix.height())
        r = self._pix.scaled(w, h, Qt.KeepAspectRatio, Qt.SmoothTransformation)
        # esquinas redondeadas
        out = QPixmap(r.size()); out.fill(Qt.transparent)
        p = QPainter(out); p.setRenderHint(QPainter.Antialiasing)
        from PySide6.QtGui import QPainterPath
        path = QPainterPath(); path.addRoundedRect(0, 0, r.width(), r.height(), 8, 8)
        p.setClipPath(path); p.drawPixmap(0, 0, r); p.end()
        self.setPixmap(out)
        self.setFixedHeight(h)


class Grafica(QWidget):
    """Error por vuelta: entrenamiento (línea) y fotos apartadas (punteada)."""

    def __init__(self):
        super().__init__()
        self.a, self.b = [], []
        self.setMinimumHeight(220)

    def poner(self, a, b):
        self.a, self.b = list(a or []), list(b or [])
        self.update()

    def paintEvent(self, e):
        p = QPainter(self); p.setRenderHint(QPainter.Antialiasing)
        W, H = self.width(), self.height()
        v = self.a + self.b
        if len(v) < 2:
            p.setPen(QColor(MUTED)); p.drawText(self.rect(), Qt.AlignCenter, "La curva aparece después de 2 vueltas."); return
        mx, mn, n = max(v), min(v), max(len(self.a), len(self.b))
        x0, y0, ancho, alto = 10, 10, W - 20, H - 34

        def linea(s, color, punteada):
            pen = QPen(QColor(color), 2.5 if not punteada else 2)
            if punteada:
                pen.setStyle(Qt.DashLine)
            p.setPen(pen)
            pts = [QPointF(x0 + i * ancho / max(1, n - 1), y0 + alto * (1 - (y - mn) / max(1e-9, mx - mn))) for i, y in enumerate(s)]
            if len(pts) == 1:
                p.drawEllipse(pts[0], 2, 2)
            else:
                p.drawPolyline(QPolygonF(pts))

        p.setPen(QPen(QColor("#1F2923"), 1))
        for k in range(4):
            yy = y0 + alto * k / 3
            p.drawLine(x0, int(yy), x0 + ancho, int(yy))
        linea(self.a, ACC, False)
        linea(self.b, "#E8A04A", True)
        p.setPen(QColor(MUTED)); f = p.font(); f.setPointSizeF(9); p.setFont(f)
        p.drawText(x0, H - 6, "vuelta 1")
        p.drawText(x0 + ancho - 70, H - 6, f"vuelta {n}")


def stat(titulo):
    f, lay = caja()
    t = etiqueta(titulo, muted=True)
    v = QLabel("—"); v.setStyleSheet("font-size: 18px; font-weight: 600;")
    lay.addWidget(t); lay.addWidget(v)
    return f, v


def fmt_tiempo(s):
    if s is None:
        return "—"
    h, m = int(s // 3600), int(s % 3600 // 60)
    return f"~{h} h {m} min" if h else f"~{m} min"


# ---------------------------------------------------------------- pantallas
class Pantalla(QWidget):
    def __init__(self, app):
        super().__init__()
        self.app = app
        self.setObjectName("fondo")
        self.lay = QVBoxLayout(self)
        self.lay.setContentsMargins(26, 22, 26, 22)
        self.lay.setSpacing(14)

    @property
    def m(self):
        return self.app.modelo

    def mostrar(self):
        pass

    def tic(self):
        pass


class TarjetaPar(QFrame):
    """Un par: miniatura antes|después y su nombre. Clic = usarlo o excluirlo."""
    ANCHO = 210
    clic = Signal(str)

    def __init__(self, p, texto):
        super().__init__()
        self.nombre = p["nombre"]
        self.setFixedWidth(self.ANCHO)
        self.setCursor(Qt.PointingHandCursor)
        lay = QVBoxLayout(self); lay.setContentsMargins(0, 0, 0, 6); lay.setSpacing(4)
        self.foto = QLabel(); self.foto.setFixedSize(self.ANCHO - 2, 84); self.foto.setAlignment(Qt.AlignCenter)
        self.foto.setStyleSheet("background: #0F1411; border-top-left-radius: 8px; border-top-right-radius: 8px;")
        self.txt = QLabel(); self.txt.setWordWrap(True)
        lay.addWidget(self.foto); lay.addWidget(self.txt)
        self.txt.setContentsMargins(8, 0, 8, 0)
        self._q = None
        self.actualizar(p, texto)

    def actualizar(self, p, texto):
        self.usar, aviso = p.get("usar", True), bool(p.get("avisos"))
        self.txt.setText(texto)
        self.txt.setStyleSheet(f"font-size: 11px; color: {WARN if aviso else MUTED};")
        self.setToolTip("\n".join(p.get("avisos") or []))
        self.setStyleSheet(f"TarjetaPar {{ background: {CARD}; border: 1px solid {'#8A6A3A' if aviso else LINE}; border-radius: 8px; }}"
                           f"TarjetaPar:hover {{ border-color: #4A5C50; }}")
        if self._q is not None:
            self.imagen(self._q)

    def imagen(self, q):
        self._q = q
        pix = QPixmap.fromImage(q).scaled(self.foto.size(), Qt.KeepAspectRatioByExpanding, Qt.SmoothTransformation)
        if not self.usar:
            tenue = QPixmap(pix.size()); tenue.fill(QColor(CARD))
            pp = QPainter(tenue); pp.setOpacity(0.3); pp.drawPixmap(0, 0, pix); pp.end()
            pix = tenue
        self.foto.setPixmap(pix)

    def mouseReleaseEvent(self, e):
        if e.button() == Qt.LeftButton:
            self.clic.emit(self.nombre)


class Pares(Pantalla):
    def __init__(self, app):
        super().__init__(app)
        self.h1 = QLabel(); self.h1.setObjectName("h1")
        self.nota = etiqueta(muted=True)
        self.lay.addWidget(self.h1); self.lay.addWidget(self.nota)

        fila = QHBoxLayout(); fila.setSpacing(10)
        self.antes, self.despues = QLineEdit(), QLineEdit()
        self.antes.setPlaceholderText(os.path.expanduser("~/Fotos/antes"))
        self.despues.setPlaceholderText(os.path.expanduser("~/Fotos/despues"))
        for titulo, campo in (("Antes (sin editar)", self.antes), ("Después (tu edición)", self.despues)):
            col = QVBoxLayout(); col.setSpacing(4)
            col.addWidget(etiqueta(titulo, muted=True))
            f2 = QHBoxLayout(); f2.setSpacing(6)
            f2.addWidget(campo, 1)
            b = boton("Elegir…"); b.clicked.connect(lambda _=False, c=campo: elegir_carpeta(self, c))
            f2.addWidget(b)
            col.addLayout(f2)
            fila.addLayout(col, 1)
        self.revisar = boton("Revisar")
        self.revisar.clicked.connect(self.hacer_revisar)
        fila.addWidget(self.revisar, 0, Qt.AlignBottom)
        self.lay.addLayout(fila)

        self.msg = etiqueta(muted=True)
        self.cuenta = QLabel()
        self.cuenta.setTextFormat(Qt.RichText)
        self.lay.addWidget(self.msg); self.lay.addWidget(self.cuenta)

        self.rejilla = QGridLayout(); self.rejilla.setSpacing(10); self.rejilla.setAlignment(Qt.AlignTop | Qt.AlignLeft)
        hv = QWidget(); hv.setLayout(self.rejilla)
        self.scroll = QScrollArea(); self.scroll.setWidgetResizable(True); self.scroll.setWidget(hv)
        self.lay.addWidget(self.scroll, 1)
        self.tarjetas = []      # [(nombre, Tarjeta)]
        self._cols = 0

        pie = QHBoxLayout(); pie.addStretch(1)
        self.sig = boton("Siguiente: ampliar pares  ›", "main")
        self.sig.clicked.connect(lambda: self.app.ir(1))
        pie.addWidget(self.sig)
        self.lay.addLayout(pie)
        self.minis = {}       # (modelo, nombre) -> QImage
        self._gen = 0

    def mostrar(self):
        P = nucleo.leer(self.m)
        self.h1.setText(f"Tus pares · {nucleo.NOMBRES[self.m]}")
        self.nota.setText("Dos carpetas: tus fotos sin editar y tu edición, con el mismo nombre de archivo."
                          + (" Sirven fotos de cara y de cuerpo: se aprende de toda la piel." if self.m == "piel" else ""))
        self.antes.setText(P.get("antes_dir", "")); self.despues.setText(P.get("despues_dir", ""))
        self.msg.setText("")
        self.pintar(P)

    def pintar(self, P):
        ps = P.get("pares", [])
        ok = sum(1 for p in ps if p.get("usar", True))
        avisos = sum(1 for p in ps if p.get("avisos"))
        sin = len(P.get("sin_pareja") or [])
        if ps:
            extra = (f" · {avisos} con aviso" if avisos else "") + (f" · {sin} sin pareja" if sin else "")
            self.cuenta.setText(f'<b style="color:{ACC}">{ok} pares listos</b><span style="color:{WARN}">{extra}</span>'
                                f'<span style="color:{MUTED}"> · haz clic en un par para usarlo o excluirlo</span>')
        else:
            self.cuenta.setText(f'<span style="color:{MUTED}">Elige tus dos carpetas y haz clic en Revisar.</span>')
        self.sig.setVisible(bool(ps))
        limpiar(self.rejilla); self.tarjetas = []; self._cols = 0
        faltan = []
        for p in ps:
            t = TarjetaPar(p, self._texto(p))
            t.clic.connect(self.alternar)
            self.tarjetas.append((p["nombre"], t))
            q = self.minis.get((self.m, p["nombre"]))
            if q is not None:
                t.imagen(q)
            else:
                faltan.append(p)
        self._acomodar()
        if faltan:
            self._gen += 1
            gen, m = self._gen, self.m

            def trabajo(avance):
                for p in faltan:
                    if gen != self._gen:
                        return
                    try:
                        a = datos.abrir(p["antes"], 320); d = datos.abrir(p["despues"], 320)
                        W, H = 104, 84
                        hoja = datos.Image.new("RGB", (2 * W, H))
                        for i, im in enumerate((a, d)):
                            k = max(W / im.width, H / im.height)
                            im = im.resize((max(W, round(im.width * k)), max(H, round(im.height * k))))
                            x, y = (im.width - W) // 2, (im.height - H) // 2
                            hoja.paste(im.crop((x, y, x + W, y + H)), (i * W, 0))
                        avance((gen, m, p["nombre"], a_qimage(hoja)))
                    except Exception:  # noqa: BLE001
                        pass

            en_fondo(trabajo, avance=self._llego)

    def _texto(self, p):
        t = p["nombre"]
        if p.get("avisos"):
            t += " · " + p["avisos"][0]
        elif p.get("nota") and p["nota"] != "ya estaba alineado":
            t += " · " + p["nota"]
        if not p.get("usar", True):
            t += " · excluido"
        return t

    def _acomodar(self):
        cols = max(1, (self.scroll.viewport().width() - 4) // (TarjetaPar.ANCHO + 10))
        if cols == self._cols:
            return
        self._cols = cols
        while self.rejilla.count():
            self.rejilla.takeAt(0)
        for i, (_, t) in enumerate(self.tarjetas):
            self.rejilla.addWidget(t, i // cols, i % cols)

    def resizeEvent(self, e):
        super().resizeEvent(e)
        self._acomodar()

    def _llego(self, r):
        gen, m, nombre, q = r
        self.minis[(m, nombre)] = q
        if gen != self._gen or m != self.m:
            return
        for n, t in self.tarjetas:
            if n == nombre:
                t.imagen(q); break

    def hacer_revisar(self):
        a, d, m = self.antes.text().strip(), self.despues.text().strip(), self.m
        self.msg.setText("Revisando… (puede tardar con muchas fotos)")
        self.revisar.setEnabled(False)

        def listo(P):
            self.revisar.setEnabled(True); self.msg.setText("")
            if m == self.m:
                self.pintar(P)
            self.app.refrescar_lado()

        def fallo(e):
            self.revisar.setEnabled(True); self.msg.setText(f'<span style="color:{BAD}">{e}</span>')

        def avance(r):
            i, n = r
            self.msg.setText(f"Revisando y alineando {i} de {n}…" if i < n else "Guardando…")

        en_fondo(lambda av: nucleo.revisar_pares(m, a, d, lambda i, n: av((i, n))), listo, fallo, avance)

    def alternar(self, nombre):
        P = nucleo.leer(self.m)
        p = next(x for x in P["pares"] if x["nombre"] == nombre)
        P = nucleo.usar(self.m, nombre, not p.get("usar", True))
        p = next(x for x in P["pares"] if x["nombre"] == nombre)
        for n, t in self.tarjetas:
            if n == nombre:
                t.actualizar(p, self._texto(p))
        self.pintar_cuenta(P, sum(1 for x in P["pares"] if x.get("usar", True)))
        self.app.refrescar_lado()

    def pintar_cuenta(self, P, ok):
        ps = P["pares"]
        avisos = sum(1 for p in ps if p.get("avisos"))
        sin = len(P.get("sin_pareja") or [])
        extra = (f" · {avisos} con aviso" if avisos else "") + (f" · {sin} sin pareja" if sin else "")
        self.cuenta.setText(f'<b style="color:{ACC}">{ok} pares listos</b><span style="color:{WARN}">{extra}</span>'
                            f'<span style="color:{MUTED}"> · haz clic en un par para usarlo o excluirlo</span>')


class Aumentos(Pantalla):
    CAMPOS_DOS = [("voltear", "Voltear horizontal", None), ("recorte_min", "Recorte mínimo (parte de la foto)", (0.3, 1, 0.05, 2)),
                  ("girar", "Girar hasta (grados)", (0, 15, 1, 0))]
    CAMPOS_ANTES = [("exposicion", "Exposición ± (pasos)", (0, 2, 0.1, 1)), ("temperatura", "Balance de blancos ± (K)", (0, 3000, 100, 0)),
                    ("contraste", "Contraste ±", (0, 0.6, 0.05, 2)), ("saturacion", "Saturación ±", (0, 0.6, 0.05, 2)),
                    ("ruido", "Ruido de celular", None), ("jpeg", "Compresión JPEG", None)]

    def __init__(self, app):
        super().__init__(app)
        self.h1 = QLabel(); self.h1.setObjectName("h1")
        self.lay.addWidget(self.h1)
        self.lay.addWidget(etiqueta("Se generan al vuelo en cada vuelta: no ocupan disco.", muted=True))
        cuerpo = QHBoxLayout(); cuerpo.setSpacing(16)
        izq = QVBoxLayout(); izq.setSpacing(12)
        self.controles = {}
        f1, l1 = caja()
        l1.addWidget(QLabel("<b>A los dos lados igual</b>"))
        l1.addWidget(etiqueta("Cambian el encuadre, no tu edición.", muted=True))
        for c in self.CAMPOS_DOS:
            l1.addLayout(self._control(*c))
        f2, l2 = caja()
        l2.addWidget(QLabel("<b>Solo en el «antes»</b>"))
        self.nota_antes = etiqueta(muted=True)
        l2.addWidget(self.nota_antes)
        for c in self.CAMPOS_ANTES:
            l2.addLayout(self._control(*c))
        f3, l3 = caja("aviso")
        n3 = etiqueta("Tu «después» nunca cambia de color: es lo que el modelo debe aprender.")
        n3.setStyleSheet("color: #F0D2A6;")
        l3.addWidget(n3)
        for f in (f1, f2, f3):
            izq.addWidget(f)
        izq.addStretch(1)
        envoltura = QWidget(); envoltura.setLayout(izq); envoltura.setFixedWidth(380)
        cuerpo.addWidget(envoltura)

        der = QVBoxLayout(); der.setSpacing(10)
        cab = QHBoxLayout()
        self.vnom = etiqueta("Ejemplos (izquierda «antes», derecha tu edición)", muted=True)
        cab.addWidget(self.vnom, 1)
        self.ver = boton("Ver ejemplos"); self.ver.clicked.connect(self.ver_ejemplos)
        cab.addWidget(self.ver)
        der.addLayout(cab)
        self.vistas = QGridLayout(); self.vistas.setSpacing(10)
        self.vistas.setColumnStretch(0, 1); self.vistas.setColumnStretch(1, 1); self.vistas.setAlignment(Qt.AlignTop)
        hv = QWidget(); hv.setLayout(self.vistas)
        sc = QScrollArea(); sc.setWidgetResizable(True); sc.setWidget(hv)
        der.addWidget(sc, 1)
        fv, lv = caja()
        filav = QHBoxLayout()
        colv = QVBoxLayout()
        colv.addWidget(etiqueta("Variantes por par", muted=True))
        self.slider = QSlider(Qt.Horizontal); self.slider.setRange(1, 60)
        self.slider.valueChanged.connect(self._variantes)
        colv.addWidget(self.slider)
        filav.addLayout(colv, 1)
        colt = QVBoxLayout()
        self.cuenta = etiqueta(muted=True); self.cuenta.setAlignment(Qt.AlignRight)
        self.total = QLabel(); self.total.setStyleSheet(f"font-size: 22px; font-weight: 600; color: {ACC};")
        self.total.setAlignment(Qt.AlignRight)
        colt.addWidget(self.cuenta); colt.addWidget(self.total)
        filav.addLayout(colt)
        lv.addLayout(filav)
        der.addWidget(fv)
        pie = QHBoxLayout(); pie.addStretch(1)
        sig = boton("Siguiente: entrenar  ›", "main"); sig.clicked.connect(lambda: self.app.ir(2))
        pie.addWidget(sig)
        der.addLayout(pie)
        cuerpo.addLayout(der, 1)
        self.lay.addLayout(cuerpo, 1)
        self._cargando = False

    def _control(self, k, texto, rango):
        fila = QHBoxLayout()
        lb = QLabel(texto)
        fila.addWidget(lb, 1)
        if rango is None:
            w = QCheckBox(); w.toggled.connect(self._guardar)
        else:
            mn, mx, paso, dec = rango
            w = QDoubleSpinBox() if dec else QSpinBox()
            if dec:
                w.setDecimals(dec)
            w.setRange(mn, mx); w.setSingleStep(paso); w.setFixedWidth(100)
            w.valueChanged.connect(self._guardar)
        self.controles[k] = w
        fila.addWidget(w)
        return fila

    def mostrar(self):
        self._cargando = True
        P = nucleo.leer(self.m)
        a = dict(datos.AUMENTOS_BASE, **P.get("aumentos", {}))
        self.h1.setText(f"Ampliar pares · {nucleo.NOMBRES[self.m]}")
        self.nota_antes.setText("En la piel se usan mínimos (el retoque no es de color)." if self.m == "piel"
                                else "Fotos de partida distintas: aprende a llegar a tu edición desde cualquiera.")
        for k, w in self.controles.items():
            if isinstance(w, QCheckBox):
                w.setChecked(bool(a[k]))
            else:
                w.setValue(a[k])
        self.slider.setValue(int(a["variantes"]))
        self._cargando = False
        self._variantes(self.slider.value(), guardar=False)
        limpiar(self.vistas)
        self.vnom.setText("Ejemplos (izquierda «antes», derecha tu edición)")
        if nucleo.resumen(self.m)["usar"]:
            self.ver_ejemplos()

    def _leer(self):
        a = {k: (w.isChecked() if isinstance(w, QCheckBox) else w.value()) for k, w in self.controles.items()}
        a["variantes"] = self.slider.value()
        return a

    def _guardar(self, *_):
        if not self._cargando:
            nucleo.poner_aumentos(self.m, self._leer())

    def _variantes(self, v, guardar=True):
        n = nucleo.resumen(self.m)["usar"]
        self.cuenta.setText(f"{n} pares × {v}")
        self.total.setText(f"{n * v:,} ejemplos")
        if guardar:
            self._guardar()

    def ver_ejemplos(self):
        limpiar(self.vistas)
        self.vnom.setText("Generando…")
        self.ver.setEnabled(False)
        m = self.m

        def trabajo(_):
            nombre, hojas = nucleo.vista_aumentos(m)
            return nombre, [a_qimage(h) for h in hojas]

        def listo(r):
            self.ver.setEnabled(True)
            nombre, qs = r
            self.vnom.setText(f"{nombre} · 8 variantes (la primera es el original)")
            for i, q in enumerate(qs):
                self.vistas.addWidget(Imagen(QPixmap.fromImage(q)), i // 2, i % 2)

        def fallo(e):
            self.ver.setEnabled(True); self.vnom.setText(f'<span style="color:{BAD}">{e}</span>')

        en_fondo(trabajo, listo, fallo)


class Entrenar(Pantalla):
    def __init__(self, app):
        super().__init__(app)
        cab = QHBoxLayout(); cab.setSpacing(10)
        self.h1 = QLabel(); self.h1.setObjectName("h1")
        cab.addWidget(self.h1, 1)
        self.lb_vueltas = etiqueta("Vueltas", muted=True, wrap=False)
        self.vueltas = QSpinBox(); self.vueltas.setRange(1, 500); self.vueltas.setFixedWidth(80)
        self.cero = QCheckBox("desde cero")
        self.cero.setToolTip("Borra lo entrenado de este modelo y empieza otra vez")
        self.ir = boton("Empezar", "main"); self.ir.clicked.connect(self.empezar)
        self.pausa = boton("Pausar"); self.pausa.clicked.connect(self.pausar)
        self.alto = boton("Detener y guardar", "peligro"); self.alto.clicked.connect(lambda: self._control("detener"))
        for w in (self.lb_vueltas, self.vueltas, self.cero, self.ir, self.pausa, self.alto):
            cab.addWidget(w)
        self.lay.addLayout(cab)
        self.msg = etiqueta(muted=True)
        self.lay.addWidget(self.msg)
        fila = QHBoxLayout(); fila.setSpacing(12)
        self.s_vuelta, self.s_falta, self.s_par, self.s_mejor = [None] * 4
        cajas = []
        for t in ("Vuelta", "Falta", "Parecido a tu edición", "Mejor guardado"):
            f, v = stat(t); fila.addWidget(f); cajas.append(v)
        self.s_vuelta, self.s_falta, self.s_par, self.s_mejor = cajas
        self.s_par.setStyleSheet(f"font-size: 18px; font-weight: 600; color: {ACC};")
        self.lay.addLayout(fila)
        self.barra = QProgressBar(); self.barra.setTextVisible(False); self.barra.setRange(0, 1000); self.barra.setFixedHeight(8)
        self.lay.addWidget(self.barra)
        cuerpo = QHBoxLayout(); cuerpo.setSpacing(16)
        fg, lg = caja()
        self.grafica = Grafica()
        lg.addWidget(self.grafica, 1)
        ley = QLabel(f'<span style="color:{ACC}">— entrenamiento</span> · <span style="color:#E8A04A">- - fotos apartadas</span>'
                     f' <span style="color:{MUTED}">(más bajo es mejor)</span>')
        ley.setStyleSheet("font-size: 12px;")
        lg.addWidget(ley)
        lg.addWidget(etiqueta("Se apartan fotos que nunca ve al entrenar para medir si aprende y no solo memoriza. "
                              "Se guarda una copia en cada vuelta: si cierras la app o se apaga la PC, sigue desde la última.", muted=True))
        cuerpo.addWidget(fg, 1)
        fm, lm = caja()
        lm.addWidget(QLabel("<b>Ejemplos de la última vuelta</b>"))
        lm.addWidget(etiqueta("antes · modelo · tu edición", muted=True))
        self.muestra = Imagen(alto_max=520)
        self.sin_muestra = etiqueta("Aparecen al terminar la primera vuelta.", muted=True)
        lm.addWidget(self.muestra); lm.addWidget(self.sin_muestra); lm.addStretch(1)
        fm.setFixedWidth(470)
        cuerpo.addWidget(fm)
        self.lay.addLayout(cuerpo, 1)
        self._muestra_f, self._muestra_t = None, None
        self._historial = {}

    def mostrar(self):
        self.h1.setText(f"Entrenar · {nucleo.NOMBRES[self.m]}")
        P = nucleo.leer(self.m)
        self.vueltas.setValue(P.get("config", {}).get(self.m, {}).get("vueltas") or (40 if self.m == "estilo" else 60))
        self.cero.setChecked(False)
        self._muestra_f = None
        m = self.m
        if m not in self._historial and not nucleo.corrida(m):
            def listo(h):
                self._historial[m] = h
                if self.m == m:
                    self.tic()
            en_fondo(lambda _: nucleo.historial(m), listo, lambda e: None)
        self.tic()

    def tic(self):
        m = self.m
        c = nucleo.corrida(m) or self._historial.get(m)
        fase = c["fase"] if c else "sin empezar"
        activo = nucleo.activo(m)
        for w in (self.lb_vueltas, self.vueltas, self.cero, self.ir):
            w.setVisible(not activo)
        self.pausa.setVisible(activo); self.alto.setVisible(activo)
        self.pausa.setText("Seguir" if fase == "en pausa" else "Pausar")
        self.ir.setText("Seguir entrenando" if nucleo.resumen(m)["mejor"] else "Empezar")
        if c:
            self.msg.setText(c["mensaje"] or fase)
            if fase == "error":
                self.msg.setStyleSheet(f"color: {BAD};")
            else:
                self.msg.setStyleSheet("")
            self.s_vuelta.setText(f"{c['vuelta']} de {c['vueltas']}" if c["vueltas"] else "—")
            self.s_falta.setText(fmt_tiempo(c["falta_s"]) if activo else ("—" if c["vuelta"] < c["vueltas"] else "listo"))
            self.s_par.setText(f"{c['parecido']} %" if c["parecido"] is not None else "—")
            self.s_mejor.setText(f"vuelta {c['mejor']['vuelta']}" if c["mejor"] else "—")
            prog = (c["vuelta"] + (c["paso"] or 0) / max(1, c["pasos"])) / c["vueltas"] if c["vueltas"] and activo else (
                c["vuelta"] / c["vueltas"] if c["vueltas"] else 0)
            self.barra.setValue(int(1000 * min(1, prog)))
            self.grafica.poner(c["perdida"], c["prueba"])
        else:
            self.msg.setStyleSheet("")
            self.msg.setText("Tablas de color 3D + red diminuta · piezas de 384 px · lote 8" if m == "estilo"
                             else "U-Net pequeña · piezas de piel de 512 px de todo el cuerpo · lote 4 · media precisión")
            for v in (self.s_vuelta, self.s_falta, self.s_par, self.s_mejor):
                v.setText("—")
            self.barra.setValue(0)
            self.grafica.poner([], [])
        f = nucleo.ultima_muestra(m)
        if f != self._muestra_f or (f and self._muestra_t != os.path.getmtime(f)):
            self._muestra_f = f
            if f:
                self._muestra_t = os.path.getmtime(f)
                self.muestra.poner(QPixmap(f)); self.muestra.show(); self.sin_muestra.hide()
            else:
                self.muestra.hide(); self.sin_muestra.show()

    def empezar(self):
        try:
            if self.cero.isChecked():
                r = QMessageBox.question(self, "Desde cero", f"¿Borrar lo entrenado de {nucleo.NOMBRES[self.m]} y empezar otra vez?")
                if r != QMessageBox.Yes:
                    return
            nucleo.entrenar(self.m, self.vueltas.value(), self.cero.isChecked())
            self._muestra_f = None
        except nucleo.Aviso as e:
            QMessageBox.information(self, "Entrenar", str(e))
        self.tic()

    def pausar(self):
        c = nucleo.corrida(self.m)
        self._control("seguir" if c and c["fase"] == "en pausa" else "pausar")

    def _control(self, accion):
        try:
            nucleo.control(self.m, accion)
        except nucleo.Aviso as e:
            QMessageBox.information(self, "Entrenar", str(e))
        self.tic()


class Exportar(Pantalla):
    def __init__(self, app):
        super().__init__(app)
        self.h1 = QLabel(); self.h1.setObjectName("h1")
        self.lay.addWidget(self.h1)
        self.falta, lf = caja()
        a = etiqueta("Primero entrena: aún no hay un modelo guardado."); a.setStyleSheet(f"color: {WARN};")
        lf.addWidget(a)
        self.lay.addWidget(self.falta)
        cuerpo = QHBoxLayout(); cuerpo.setSpacing(16)
        izq = QVBoxLayout(); izq.setSpacing(10)
        fila = QHBoxLayout()
        self.carpeta = QLineEdit(); self.carpeta.setPlaceholderText("Carpeta con fotos nuevas que el modelo nunca vio")
        fila.addWidget(self.carpeta, 1)
        b = boton("Elegir…"); b.clicked.connect(lambda: elegir_carpeta(self, self.carpeta)); fila.addWidget(b)
        self.probar = boton("Probar"); self.probar.clicked.connect(self.hacer_probar); fila.addWidget(self.probar)
        izq.addLayout(fila)
        self.pmsg = etiqueta(muted=True)
        izq.addWidget(self.pmsg)
        self.res = QVBoxLayout(); self.res.setSpacing(14)
        hv = QWidget(); hv.setLayout(self.res)
        sc = QScrollArea(); sc.setWidgetResizable(True); sc.setWidget(hv)
        izq.addWidget(sc, 1)
        cuerpo.addLayout(izq, 1)
        fe, le = caja("acento")
        le.addWidget(QLabel("<b>Exportar para el teléfono</b>"))
        self.exp_nota = etiqueta(muted=True)
        le.addWidget(self.exp_nota)
        self.versiones = etiqueta(muted=True)
        le.addWidget(self.versiones)
        self.exp = boton("Exportar la mejor vuelta", "main"); self.exp.clicked.connect(self.hacer_exportar)
        le.addWidget(self.exp)
        self.emsg = etiqueta(muted=True)
        le.addWidget(self.emsg)
        self.abrir = boton("Abrir la carpeta"); self.abrir.hide()
        self.abrir.clicked.connect(self._abrir_carpeta)
        le.addWidget(self.abrir)
        le.addStretch(1)
        fe.setFixedWidth(380)
        lado_der = QVBoxLayout(); lado_der.setSpacing(16)
        lado_der.addWidget(fe)
        fn, ln = caja()
        ln.addWidget(QLabel("<b>Versión NPU</b>"))
        ln.addWidget(etiqueta("Compila la última versión exportada para el procesador de IA del teléfono (W8A16, "
                              "QNN 2.42, Hexagon v73), calibrada con tus propias fotos. Tarda unos minutos.", muted=True))
        self.npu = boton("Crear versión NPU"); self.npu.clicked.connect(self.hacer_npu)
        ln.addWidget(self.npu)
        self.nmsg = etiqueta(muted=True)
        ln.addWidget(self.nmsg)
        fn.setFixedWidth(380)
        lado_der.addWidget(fn)
        lado_der.addStretch(1)
        cuerpo.addLayout(lado_der)
        self.lay.addLayout(cuerpo, 1)
        self._ultima = None

    def mostrar(self):
        m = self.m
        r = nucleo.resumen(m)
        self.h1.setText(f"Probar y exportar · {nucleo.NOMBRES[m]}")
        self.falta.setVisible(not r["mejor"])
        self.exp.setEnabled(r["mejor"]); self.probar.setEnabled(r["mejor"])
        self.carpeta.setText(nucleo.leer(m).get("prueba_dir", ""))
        self.exp_nota.setText(f"Crea la carpeta proyectos/{m}/exportado/{m}_vN con el modelo (.onnx) y su ficha. "
                              "Solo sale el modelo: tus fotos se quedan en esta PC. El ONNX se revisa contra PyTorch.")
        self.versiones.setText("Versiones: " + (", ".join(r["exportados"]) or "ninguna"))
        self.emsg.setText(""); self.abrir.hide(); self.pmsg.setText("")
        self.npu.setEnabled(bool(r["exportados"]))
        if not r["exportados"]:
            self.nmsg.setText("Primero exporta el modelo.")
        elif not nucleo.npu_listo():
            self.nmsg.setText("Falta el kit de Qualcomm (una sola vez, unos 3 GB): en la carpeta del entrenador corre "
                              "<b>bash instalar_npu.sh</b>")
        else:
            self.nmsg.setText(f"Se hará de {r['exportados'][-1]}.")
        limpiar(self.res)

    def hacer_probar(self):
        limpiar(self.res)
        self.pmsg.setText("Probando…"); self.probar.setEnabled(False)
        m, d = self.m, self.carpeta.text().strip()

        def listo(hechos):
            self.probar.setEnabled(True)
            self.pmsg.setText("" if hechos else "No encontré fotos en esa carpeta.")
            for h in hechos:
                self.res.addWidget(Imagen(QPixmap(h["archivo"])))
                self.res.addWidget(etiqueta(f"{h['nombre']} · {h['ms']} ms · izquierda antes, derecha modelo", muted=True))
            self.res.addStretch(1)

        def fallo(e):
            self.probar.setEnabled(True); self.pmsg.setText(f'<span style="color:{BAD}">{e}</span>')

        en_fondo(lambda _: nucleo.probar(m, d), listo, fallo)

    def hacer_exportar(self):
        self.emsg.setText("Exportando…"); self.exp.setEnabled(False); self.abrir.hide()
        m = self.m

        def listo(r):
            self.exp.setEnabled(True)
            dif = "sin revisar" if r.get("diferencia_onnx") is None else f"{r['diferencia_onnx']:.1e}"
            self.emsg.setText(f"Listo: <b>{r['archivo']}</b> v{r['version']} · {r['mb']} MB · diferencia contra PyTorch {dif}"
                              f"<br>{r['carpeta']}")
            self._ultima = r["carpeta"]; self.abrir.show()
            self.versiones.setText("Versiones: " + (", ".join(nucleo.resumen(m)["exportados"]) or "ninguna"))

        def fallo(e):
            self.exp.setEnabled(True); self.emsg.setText(f'<span style="color:{BAD}">{e}</span>')

        en_fondo(lambda _: nucleo.exportar(m), listo, fallo)

    def hacer_npu(self):
        self.npu.setEnabled(False); self.nmsg.setText("Empezando…")
        m = self.m

        def listo(r):
            self.npu.setEnabled(True)
            mins, seg = divmod(r["segundos"], 60)
            self.nmsg.setText(f"Listo en {mins} min {seg} s: <b>{os.path.basename(r['bin'])}</b> ({r['mb']} MB) y "
                              f"<b>{os.path.basename(r['ctx'])}</b><br>{r['carpeta']}")
            self._ultima = r["carpeta"]; self.abrir.show()

        def fallo(e):
            self.npu.setEnabled(True); self.nmsg.setText(f'<span style="color:{BAD}">{e}</span>')

        en_fondo(lambda av: nucleo.crear_npu(m, av), listo, fallo, lambda t: self.nmsg.setText(str(t)))

    def _abrir_carpeta(self):
        from PySide6.QtCore import QUrl
        from PySide6.QtGui import QDesktopServices
        if self._ultima:
            QDesktopServices.openUrl(QUrl.fromLocalFile(self._ultima))


# ---------------------------------------------------------------- ventana
class Ventana(QMainWindow):
    PASOS = ("Tus pares", "Ampliar pares", "Entrenar", "Probar y exportar")

    def __init__(self):
        super().__init__()
        self.setWindowTitle("Nítido · Entrenador")
        self.resize(1320, 860)
        self.setMinimumSize(1080, 700)
        self.modelo = "estilo"
        raiz = QWidget(); raiz.setObjectName("fondo")
        h = QHBoxLayout(raiz); h.setContentsMargins(0, 0, 0, 0); h.setSpacing(0)

        lado = QWidget(); lado.setObjectName("lado"); lado.setFixedWidth(240)
        lv = QVBoxLayout(lado); lv.setContentsMargins(14, 18, 14, 18); lv.setSpacing(6)
        t = QLabel("Nítido · Entrenador"); t.setObjectName("titulo")
        lv.addWidget(t)
        tabs = QHBoxLayout(); tabs.setContentsMargins(2, 0, 2, 12); tabs.setSpacing(8)
        self.g_mod = QButtonGroup(self)
        for i, (m, texto) in enumerate((("estilo", "Mi estilo"), ("piel", "Mi piel"))):
            b = boton(texto, "tab"); b.setCheckable(True); b.setChecked(i == 0)
            self.g_mod.addButton(b, i); tabs.addWidget(b)
        tabs.addStretch(1)
        self.g_mod.idClicked.connect(lambda i: self.cambiar_modelo(nucleo.MODELOS[i]))
        lv.addLayout(tabs)
        self.g_paso = QButtonGroup(self)
        for i, texto in enumerate(self.PASOS):
            b = boton(f"  {i + 1}    {texto}", "paso"); b.setCheckable(True); b.setChecked(i == 0)
            self.g_paso.addButton(b, i); lv.addWidget(b)
        self.g_paso.idClicked.connect(self.ir)
        lv.addStretch(1)
        self.resumen = etiqueta(muted=True)
        lv.addWidget(self.resumen)
        fg, lg = caja()
        self.gpu = QLabel(); self.gpu.setWordWrap(True); self.gpu.setTextFormat(Qt.RichText)
        self.gpu.setStyleSheet(f"color: {MUTED}; font-size: 12px;")
        lg.addWidget(self.gpu)
        lv.addWidget(fg)
        h.addWidget(lado)

        self.pila = QStackedWidget()
        self.pantallas = [Pares(self), Aumentos(self), Entrenar(self), Exportar(self)]
        for p in self.pantallas:
            self.pila.addWidget(p)
        h.addWidget(self.pila, 1)
        self.setCentralWidget(raiz)

        self._gpu = (None, {})
        self.ir(0)
        self.refrescar_lado()
        self.reloj = QTimer(self); self.reloj.timeout.connect(self._tic); self.reloj.start(1000)
        self._n = 0
        en_fondo(lambda _: nucleo.tarjeta(), self._llego_gpu, lambda e: self._llego_gpu((None, {})))

    def ir(self, i):
        self.g_paso.button(i).setChecked(True)
        self.pila.setCurrentIndex(i)
        self.pantallas[i].mostrar()

    def cambiar_modelo(self, m):
        self.modelo = m
        self.pantallas[self.pila.currentIndex()].mostrar()
        self.refrescar_lado()

    def refrescar_lado(self):
        r = nucleo.resumen(self.modelo)
        partes = [f"{r['usar']} pares"]
        if r["mejor"]:
            partes.append("modelo guardado")
        if r["exportados"]:
            partes.append(f"{len(r['exportados'])} exportado(s)")
        otro = [m for m in nucleo.MODELOS if m != self.modelo and nucleo.activo(m)]
        if otro:
            partes.append(f"entrenando {nucleo.NOMBRES[otro[0]]}")
        self.resumen.setText(" · ".join(partes))

    def _llego_gpu(self, r):
        self._gpu = r
        self._pintar_gpu()

    def _pintar_gpu(self):
        nombre, info = self._gpu
        if nombre:
            extra = []
            if info.get("temp") is not None:
                extra.append(f"{info['temp']} °C")
            if info.get("vram") is not None:
                extra.append(f"memoria {info['vram']} de {info['vram_total']} GB")
            self.gpu.setText(f'<b style="color:{FG}">{nombre}</b><br>{" · ".join(extra)}')
        else:
            self.gpu.setText(f'<b style="color:{WARN}">Sin tarjeta NVIDIA</b><br>se usa el procesador (lento)')

    def _tic(self):
        self._n += 1
        if self.pila.currentIndex() == 2:
            self.pantallas[2].tic()
        if self._n % 5 == 0:
            self.refrescar_lado()
            if self._gpu[0]:
                from .entrenar import _gpu
                en_fondo(lambda _: (self._gpu[0], _gpu()), self._llego_gpu)

    def closeEvent(self, e):
        if any(nucleo.activo(m) for m in nucleo.MODELOS):
            r = QMessageBox.question(self, "Está entrenando",
                                     "Si cierras, el entrenamiento se detiene y se guarda la última vuelta completa. "
                                     "La próxima vez sigue desde ahí.\n\n¿Cerrar de todos modos?")
            if r != QMessageBox.Yes:
                e.ignore(); return
            nucleo.detener_todo()
            for c in nucleo.corridas.values():
                c.hilo.join(timeout=15)
        e.accept()


def main():
    os.makedirs(nucleo.PROY, exist_ok=True)
    QApplication.setApplicationName("Nítido · Entrenador")
    QApplication.setDesktopFileName("nitido-entrenador")
    app = QApplication(sys.argv)
    app.setStyle("Fusion")
    f = QFont(); f.setFamilies(["Inter", "Fira Sans", "Ubuntu", "Noto Sans", "sans-serif"]); f.setPointSizeF(10.5)
    app.setFont(f)
    icono = os.path.join(nucleo.AQUI, "icono.png")
    if os.path.exists(icono):
        app.setWindowIcon(QIcon(icono))
    app.setStyleSheet(ESTILO)
    v = Ventana()
    v.show()
    if "--captura" in sys.argv:          # para revisar la pantalla sin monitor: --captura <paso> <archivo.png>
        i = sys.argv.index("--captura")
        paso, salida = int(sys.argv[i + 1]), sys.argv[i + 2]
        espera = int(sys.argv[i + 3]) if len(sys.argv) > i + 3 else 3000
        v.ir(paso)
        QTimer.singleShot(espera, lambda: (v.grab().save(salida), app.quit()))
    sys.exit(app.exec())


if __name__ == "__main__":
    main()
