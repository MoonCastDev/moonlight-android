exec(open('gen5.py').read().split('def snes_dpad')[0])   # scene, defs (grain, body...), shade, T2, game area
DEF7='''<filter id="tex" x="0" y="0" width="100%" height="100%"><feTurbulence type="fractalNoise" baseFrequency="1.6" numOctaves="2" seed="7" result="n"/><feColorMatrix in="n" values="0 0 0 0 1  0 0 0 0 1  0 0 0 0 1  0 0 0 .14 0" result="w"/><feComposite in="w" in2="SourceGraphic" operator="in" result="t"/><feMerge><feMergeNode in="SourceGraphic"/><feMergeNode in="t"/></feMerge></filter>
<linearGradient id="ps_body" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#B9BBC0"/><stop offset="1" stop-color="#A3A5AA"/></linearGradient>
<linearGradient id="key" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#55565B"/><stop offset=".55" stop-color="#3B3C40"/><stop offset="1" stop-color="#2A2B2E"/></linearGradient>
<radialGradient id="cap7" cx=".45" cy=".35" r=".75"><stop offset="0" stop-color="#55565B"/><stop offset=".7" stop-color="#36373B"/><stop offset="1" stop-color="#202124"/></radialGradient>
<radialGradient id="well7" cx=".5" cy=".55" r=".55"><stop offset=".7" stop-color="#8F9196"/><stop offset="1" stop-color="#7a7c81"/></radialGradient>
<filter id="ksh" x="-20%" y="-20%" width="140%" height="150%"><feDropShadow dx="0" dy="5" stdDeviation="4" flood-color="#000" flood-opacity=".45"/></filter>
<filter id="plate" x="-5%" y="-5%" width="110%" height="115%"><feDropShadow dx="0" dy="2" stdDeviation="1.5" flood-color="#000" flood-opacity=".18"/></filter>'''
def T7(x,y,s,t,fill='#55575c',w=800,ls=1): return T2(x,y,s,t,fill,w,ls)
def plate(x,y,r):   # the raised cross-shaped plate the d-pad and the shape buttons sit on
    a=r*.48
    return f'<path d="M{x-a} {y-r} H{x+a} V{y-a} H{x+r} V{y+a} H{x+a} V{y+r} H{x-a} V{y+a} H{x-r} V{y-a} H{x-a} Z" fill="#B0B2B7" stroke="#9c9ea3" stroke-width="2" stroke-linejoin="round" rx="10" filter="url(#plate)"/>'
def arrowkey(x,y,r,rot):   # one d-pad key: a rounded arrow pointing out
    w=r*.30; L=r*.62
    p=f'M{x-w} {y-r*.92} Q{x-w} {y-r*.98} {x-w+8} {y-r*.98} H{x+w-8} Q{x+w} {y-r*.98} {x+w} {y-r*.92} V{y-r*.92+L*.62} L{x} {y-r*.18} L{x-w} {y-r*.92+L*.62} Z'
    return f'<g transform="rotate({rot} {x} {y})"><path d="{p}" fill="url(#key)" stroke="#1d1e21" stroke-width="2.5" stroke-linejoin="round" filter="url(#ksh)"/><path d="{p}" fill="#000" filter="url(#tex)" opacity=".38"/></g>'
def dpad7(x,y,r):
    o=plate(x,y,r*1.32)
    for rot in (0,90,180,270):   # the small moulded arrows at the plate's ends
        o+=f'<path d="M{x} {y-r*1.24} l{-r*.12} {r*.12} h{r*.24} z" fill="#C8CACE" stroke="#8d8f94" stroke-width="2" transform="rotate({rot} {x} {y})"/>'
    return o+''.join(arrowkey(x,y,r,rot) for rot in (0,90,180,270))
def shape(x,y,r,k,col):
    q=r*.46; sw=r*.11
    s={'t':f'<path d="M{x} {y-q*1.15}L{x+q*1.1} {y+q*.75}H{x-q*1.1}Z" fill="none" stroke="{col}" stroke-width="{sw}" stroke-linejoin="round"/>',
       'o':f'<circle cx="{x}" cy="{y}" r="{q*1.05}" fill="none" stroke="{col}" stroke-width="{sw}"/>',
       's':f'<rect x="{x-q*.95}" y="{y-q*.95}" width="{q*1.9}" height="{q*1.9}" fill="none" stroke="{col}" stroke-width="{sw}"/>',
       'x':f'<path d="M{x-q} {y-q}L{x+q} {y+q}M{x+q} {y-q}L{x-q} {y+q}" stroke="{col}" stroke-width="{sw}" stroke-linecap="round"/>'}[k]
    return (f'<circle cx="{x}" cy="{y}" r="{r}" fill="url(#cap7)" stroke="#141518" stroke-width="3" filter="url(#ksh)"/><circle cx="{x}" cy="{y}" r="{r}" fill="#000" filter="url(#tex)" opacity=".38"/>'
            f'<circle cx="{x}" cy="{y}" r="{r*.93}" fill="none" stroke="#fff" stroke-opacity=".08" stroke-width="3"/>'+s)
def face7(x,y,r):
    d=r*.98; br=r*.46
    return plate(x,y,r*1.5)+shape(x,y-d,br,'t','#3FD3A0')+shape(x+d,y,br,'o','#F25C66')+shape(x-d,y,br,'s','#E58BD8')+shape(x,y+d,br,'x','#7BA4F2')
def shoulder7(x,y,w,h,label,round_side=None):
    rl=h*.5 if round_side=='L' else h*.2; rr=h*.5 if round_side=='R' else h*.2
    p=f'M{x+rl} {y} H{x+w-rr} Q{x+w} {y} {x+w} {y+rr} V{y+h-h*.18} Q{x+w} {y+h} {x+w-h*.18} {y+h} H{x+h*.18} Q{x} {y+h} {x} {y+h-h*.18} V{y+rl} Q{x} {y} {x+rl} {y} Z'
    return f'<path d="{p}" fill="url(#key)" stroke="#1d1e21" stroke-width="2.5" filter="url(#ksh)"/><path d="{p}" fill="#000" filter="url(#tex)" opacity=".38"/>'+T7(x+w/2,y+h/2,h*.42,label,'#2a2b2e')+T7(x+w/2-1,y+h/2-1.5,h*.42,label,'#5f6065')
def stick7(x,y,r):
    return (f'<circle cx="{x}" cy="{y}" r="{r*1.25}" fill="url(#well7)" stroke="#8a8c91" stroke-width="2"/>'
            f'<circle cx="{x}" cy="{y}" r="{r}" fill="url(#cap7)" stroke="#121315" stroke-width="5" filter="url(#ksh)"/><circle cx="{x}" cy="{y}" r="{r}" fill="#000" filter="url(#tex)" opacity=".38"/>'
            f'<circle cx="{x}" cy="{y}" r="{r*.80}" fill="none" stroke="#000" stroke-opacity=".25" stroke-width="{r*.05}"/>')
def create7(x,y,s):   # the small dark rectangle (Select's shape, PS5's Create)
    return f'<rect x="{x-s*.7}" y="{y-s*.4}" width="{s*1.4}" height="{s*.8}" rx="{s*.18}" fill="url(#key)" stroke="#1d1e21" stroke-width="2" filter="url(#ksh)"/>'+T7(x,y+s*1.05,s*.5,'CREATE')
def options7(x,y,s):  # the small triangle (Start's shape, PS5's Options)
    return f'<path d="M{x-s*.7} {y-s*.45} Q{x-s*.75} {y-s*.5} {x-s*.6} {y-s*.48} L{x+s*.75} {y-s*.05} Q{x+s*.85} {y} {x+s*.75} {y+s*.05} L{x-s*.6} {y+s*.48} Q{x-s*.75} {y+s*.5} {x-s*.7} {y+s*.4} Z" fill="url(#key)" stroke="#1d1e21" stroke-width="2" filter="url(#ksh)"/>'+T7(x,y+s*1.05,s*.5,'OPTIONS')
def gear7(x,y,r):
    teeth=''.join(f'<rect x="{x-r*.14}" y="{y-r*.62}" width="{r*.28}" height="{r*.24}" rx="2" fill="#5a5c61" transform="rotate({a} {x} {y})"/>' for a in range(0,360,45))
    return f'<circle cx="{x}" cy="{y}" r="{r}" fill="#9a9ca1" stroke="#7c7e83" stroke-width="2" filter="url(#plate)"/>'+teeth+f'<circle cx="{x}" cy="{y}" r="{r*.42}" fill="#5a5c61"/><circle cx="{x}" cy="{y}" r="{r*.18}" fill="#9a9ca1"/>'
def analog7(x,y): return T7(x,y-22,15,'ANALOG','#5f6166',700)+f'<rect x="{x-22}" y="{y-8}" width="44" height="18" rx="5" fill="#2a2b2e" stroke="#6b6d72" stroke-width="2"/><rect x="{x-17}" y="{y-3}" width="34" height="8" rx="3" fill="#3a1b1d"/>'
def touchpad7(x,y,w,h):
    return f'<rect x="{x-w/2}" y="{y-h/2}" width="{w}" height="{h}" rx="{h*.35}" fill="#9EA0A5" stroke="#86888d" stroke-width="2" filter="url(#plate)"/>'+T7(x,y,min(h*.30,w*.11),'TOUCHPAD','#6c6e73',800,1)
def bodyfill(): return 'fill="url(#ps_body)"'
gameS=scene()
def g16(x,y,w,h): return f'<svg x="{x}" y="{y}" width="{w}" height="{h}" viewBox="0 0 {W} {H}" preserveAspectRatio="xMidYMin slice">{gameS}</svg>'
css='@font-face{font-family:Figtree;src:url(Figtree.ttf);font-weight:100 900}@font-face{font-family:Unbounded;src:url(Unbounded.ttf);font-weight:100 900}html,body{margin:0;overflow:hidden;background:#000}'
# ---- upright (1248 x 1972): 16:9 game on top in its black bezel, the DualShock-style faceplate below
PW,PH=1248,1972
gh=int(PW*9/16); gy=120
o=f'<svg width="{PW}" height="{PH}" viewBox="0 0 {PW} {PH}"><defs>{DEFS}{DEF5}{DEF7}</defs><rect width="{PW}" height="{PH}" fill="#000"/>'
o+=f'<g font-family="Figtree" font-weight="600" font-size="38" fill="#ddd"><text x="40" y="62">8:24</text><text x="{PW-130}" y="62">47%</text></g>'
o+=g16(0,gy,PW,gh)
strip=gy+gh
for lx,lt in ((70,'L3'),(270,'⤓ SAVE'),(PW-270,'⤒ LOAD'),(PW-70,'R3')): o+=T7(lx,strip+48,24,lt,'#9a9ca1',700)
by=strip+96
o+=f'<path d="M0 {by+60} Q0 {by} 60 {by} H{PW-60} Q{PW} {by} {PW} {by+60} V{PH} H0 Z" {bodyfill()}/><path d="M0 {by+60} Q0 {by} 60 {by} H{PW-60} Q{PW} {by} {PW} {by+60} V{PH} H0 Z" fill="#000" filter="url(#grain)" opacity=".8"/>'
o+=f'<path d="M40 {by+600} Q40 {PH-400} 230 {PH-400} H{PW-230} Q{PW-40} {PH-400} {PW-40} {by+600}" fill="none" stroke="#9a9ca1" stroke-width="3"/>'
y1=by+40
o+=shoulder7(36,y1,270,92,'L1','L')+shoulder7(320,y1,160,92,'L2')+shoulder7(PW-480,y1,160,92,'R2')+shoulder7(PW-306,y1,270,92,'R1','R')
cy=by+340
o+=dpad7(290,cy,132)+face7(PW-290,cy,122)
o+=create7(PW/2-95,PH-330,44)+options7(PW/2+95,PH-330,44)+touchpad7(PW/2,y1+46,170,80)
o+=stick7(330,PH-240,112)+stick7(PW-330,PH-240,112)+gear7(80,PH-150,34)+analog7(PW-80,PH-160)
o+=f'<rect x="{PW/2-150}" y="{PH-40}" width="300" height="10" rx="5" fill="#d8d9dc"/></svg>'
open('v7_PS_upright.html','w').write(f'<html><head><style>{css}</style></head><body>{o}</body></html>')
# ---- sideways (1972 x 1248): the game in a black rounded bezel in the middle, the faceplate round both sides
SW=470; bx=SW; bw=W-2*SW; gh=int(bw*9/16)
o=f'<svg width="{W}" height="{H}" viewBox="0 0 {W} {H}"><defs>{DEFS}{DEF5}{DEF7}</defs><rect width="{W}" height="{H}" {bodyfill()}/><rect width="{W}" height="{H}" fill="#000" filter="url(#grain)" opacity=".8"/>'
o+=f'<rect x="{bx}" y="0" width="{bw}" height="{gh+230}" rx="60" fill="#0b0b0c"/><rect x="{bx}" y="0" width="{bw}" height="60" fill="#0b0b0c"/>'
gy=90
o+=g16(bx+16,gy,bw-32,int((bw-32)*9/16))
sy=gy+int((bw-32)*9/16)+60
for lx,lt in ((bx+70,'L3'),(bx+bw*.3,'⤓ SAVE'),(bx+bw*.7,'⤒ LOAD'),(bx+bw-70,'R3')): o+=T7(lx,sy,26,lt,'#9a9ca1',700)
o+=shoulder7(36,34,250,84,'L1','L')+shoulder7(300,34,140,84,'L2')+shoulder7(W-440,34,140,84,'R2')+shoulder7(W-286,34,250,84,'R1','R')
o+=dpad7(SW/2+10,H*.42,120)+face7(W-SW/2-10,H*.42,112)
o+=stick7(SW/2+20,H*.78,118)+stick7(W-SW/2-20,H*.78,118)
o+=create7(bx+120,H-80,40)+options7(W-bx-120,H-80,40)+touchpad7(W/2,H-110,260,90)
o+=gear7(54,H-60,26)+analog7(W-60,H-60)+'</svg>'
open('v7_PS_sideways.html','w').write(f'<html><head><style>{css}</style></head><body>{o}</body></html>')
