"""
Los dos modelos propios.

· MiEstilo: tablas de color 3D (33×33×33) que una red diminuta mezcla según cada foto («Image-Adaptive
  3D LUT»). Aprende tu color, luz y contraste, y se aplica a la foto completa a cualquier tamaño.
  Al teléfono va solo la red que mira una miniatura de 256 px y entrega la tabla; la tabla se aplica
  en la app (milisegundos).
· MiPiel: U-Net pequeña que trabaja en piezas de 512 px y entrega «la foto + un cambio» (residual):
  aprende a quitar granos, manchas y rojeces sin borrar la textura. Solo operaciones que la NPU
  conoce bien (convoluciones, ReLU, cambio de tamaño bilineal).
"""
import torch
import torch.nn as nn
import torch.nn.functional as F

LUT = 33


def identidad(n=LUT):
    g = torch.linspace(0, 1, n)
    b, gg, r = torch.meshgrid(g, g, g, indexing="ij")   # volumen [azul, verde, rojo]
    return torch.stack([r, gg, b], 0)                    # canales R, G, B


class MiEstilo(nn.Module):
    def __init__(self, bases=3, n=LUT):
        super().__init__()
        luts = torch.zeros(bases, 3, n, n, n)
        luts[0] = identidad(n)
        self.luts = nn.Parameter(luts)
        ch = [3, 16, 32, 64, 128, 128]
        capas = []
        for a, b in zip(ch[:-1], ch[1:]):
            capas += [nn.Conv2d(a, b, 3, 2, 1), nn.LeakyReLU(0.2), nn.InstanceNorm2d(b, affine=True)]
        self.cnn = nn.Sequential(*capas, nn.Dropout(0.5), nn.AdaptiveAvgPool2d(1))
        self.pesos = nn.Linear(128, bases)
        nn.init.zeros_(self.pesos.weight)
        with torch.no_grad():
            self.pesos.bias.copy_(torch.tensor([1.0] + [0.0] * (bases - 1)))

    def tabla(self, mini):
        """mini [B,3,256,256] 0..1 -> tabla [B,3,n,n,n]"""
        w = self.pesos(self.cnn(mini).flatten(1))                # [B, bases]
        return torch.einsum("kb,bcxyz->kcxyz", w, self.luts)

    @staticmethod
    def aplicar(tabla, foto):
        """foto [B,3,H,W] 0..1 -> [B,3,H,W], interpolación trilineal en la tabla."""
        B, _, H, W = foto.shape
        grid = (foto.permute(0, 2, 3, 1) * 2 - 1).clamp(-1, 1).unsqueeze(1)   # [B,1,H,W,3] = (r,g,b) -> (x,y,z)
        out = F.grid_sample(tabla, grid, mode="bilinear", padding_mode="border", align_corners=True)
        return out.view(B, 3, H, W)

    def forward(self, foto, mini=None):
        if mini is None:
            mini = F.interpolate(foto, size=(256, 256), mode="bilinear", align_corners=False)
        return self.aplicar(self.tabla(mini), foto)

    def regulares(self):
        """Tablas suaves y que no inviertan el orden (más luz entra, más luz sale)."""
        L = self.luts
        dx = L[..., :, :, 1:] - L[..., :, :, :-1]
        dy = L[..., :, 1:, :] - L[..., :, :-1, :]
        dz = L[..., 1:, :, :] - L[..., :-1, :, :]
        suave = (dx ** 2).mean() + (dy ** 2).mean() + (dz ** 2).mean()
        orden = F.relu(-dx[:, 0]).mean() + F.relu(-dy[:, 1]).mean() + F.relu(-dz[:, 2]).mean()
        return suave, orden


class TablaParaTelefono(nn.Module):
    """Lo que va al teléfono: miniatura 256 -> tabla [3,33,33,33] ya mezclada."""

    def __init__(self, m):
        super().__init__()
        self.m = m

    def forward(self, mini):
        return self.m.tabla(mini)[0]


def _bloque(a, b):
    return nn.Sequential(nn.Conv2d(a, b, 3, 1, 1), nn.ReLU(inplace=True), nn.Conv2d(b, b, 3, 1, 1), nn.ReLU(inplace=True))


class MiPiel(nn.Module):
    """U-Net residual: salida = entrada + cambio. ~9 millones de pesos con base 32."""

    def __init__(self, base=32):
        super().__init__()
        c = [base, base * 2, base * 4, base * 8, base * 8]
        self.ent = _bloque(3, c[0])
        self.baja = nn.ModuleList([nn.Conv2d(c[i], c[i], 3, 2, 1) for i in range(4)])
        self.enc = nn.ModuleList([_bloque(c[i], c[i + 1]) for i in range(4)])
        self.dec = nn.ModuleList([_bloque(c[i + 1] + c[i], c[i]) for i in reversed(range(4))])
        self.sal = nn.Conv2d(c[0], 3, 3, 1, 1)
        nn.init.zeros_(self.sal.weight); nn.init.zeros_(self.sal.bias)   # empieza sin cambiar nada

    def forward(self, x):
        h = self.ent(x * 2 - 1)
        pila = [h]
        for baja, enc in zip(self.baja, self.enc):
            h = enc(baja(h)); pila.append(h)
        pila.pop()
        for dec in self.dec:
            s = pila.pop()
            h = F.interpolate(h, size=s.shape[-2:], mode="bilinear", align_corners=False)
            h = dec(torch.cat([h, s], 1))
        return (x + self.sal(h)).clamp(0, 1)


def pesos(m):
    return sum(p.numel() for p in m.parameters())
