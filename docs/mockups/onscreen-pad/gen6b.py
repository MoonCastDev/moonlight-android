exec(open('gen6.py').read().split('styles={')[0])
def frame16(body, cap_):
    o=f'<svg width="{W}" height="{H}" viewBox="0 0 {W} {H}"><defs>{DEFS}{DEF5}{DEF6}</defs>{scene()}<g opacity="{A}">{body}</g></svg>'
    c=f'<div style="position:absolute;left:50%;transform:translateX(-50%);top:150px;font:700 25px Figtree;color:#fff;background:rgba(0,0,0,.6);padding:8px 18px;border-radius:12px;white-space:nowrap">{cap_}</div>'
    return f'<div style="position:relative;width:{W}px;height:{H}px">{o}{c}</div>'
def psb(x,y,r,k,col,base='#26272c'):
    g,d=fill(base); q=r*.42; sw=r*.13
    sym={'x':f'<path d="M{x-q} {y-q}L{x+q} {y+q}M{x+q} {y-q}L{x-q} {y+q}" stroke="{col}" stroke-width="{sw}" stroke-linecap="round"/>',
         'o':f'<circle cx="{x}" cy="{y}" r="{q*1.05}" fill="none" stroke="{col}" stroke-width="{sw}"/>',
         's':f'<rect x="{x-q*.95}" y="{y-q*.95}" width="{q*1.9}" height="{q*1.9}" rx="3" fill="none" stroke="{col}" stroke-width="{sw}"/>',
         't':f'<path d="M{x} {y-q*1.2}L{x+q*1.08} {y+q*.72}H{x-q*1.08}Z" fill="none" stroke="{col}" stroke-width="{sw}" stroke-linejoin="round"/>'}[k]
    return f'<defs>{d}</defs><circle cx="{x}" cy="{y}" r="{r}" fill="url(#{g})" stroke="{OUT}" stroke-width="4" {sh()}/>'+sym
def trig6(x,y,w,h,c,label,side):
    g,d=fill(c); k=h*.5
    p=(f'M{x-w/2} {y+h/2} V{y-h/2+k} Q{x-w/2} {y-h/2} {x-w/2+k} {y-h/2} H{x+w/2-k*.3} Q{x+w/2} {y-h/2} {x+w/2} {y} V{y+h/2} Z' if side=='L' else
       f'M{x+w/2} {y+h/2} V{y-h/2+k} Q{x+w/2} {y-h/2} {x+w/2-k} {y-h/2} H{x-w/2+k*.3} Q{x-w/2} {y-h/2} {x-w/2} {y} V{y+h/2} Z')
    return f'<defs>{d}</defs><path d="{p}" fill="url(#{g})" stroke="{OUT}" stroke-width="4" {sh()}/>'+T6(x,y+h*.06,h*.42,label)
def icon_btn(x,y,r,c,kind):
    g,d=fill(c); o=f'<defs>{d}</defs><circle cx="{x}" cy="{y}" r="{r}" fill="url(#{g})" stroke="{OUT}" stroke-width="4" {sh()}/>'
    if kind=='view': o+=f'<rect x="{x-r*.42}" y="{y-r*.34}" width="{r*.5}" height="{r*.4}" rx="3" fill="none" stroke="#fff" stroke-width="{r*.1}"/><rect x="{x-r*.14}" y="{y-r*.08}" width="{r*.5}" height="{r*.4}" rx="3" fill="{c}" stroke="#fff" stroke-width="{r*.1}"/>'
    else: o+=''.join(f'<rect x="{x-r*.38}" y="{y+dy*r-r*.05}" width="{r*.76}" height="{r*.1}" rx="2" fill="#fff"/>' for dy in (-.24,0,.24))
    return o
# shared stick-controller positions (fractions of the screen)
def common(cL,cR):
    return (stick6(*P(.13,.72),0.15*H,cL)+stick6(*P(.70,.80),0.13*H,cR))
styles={
 'DualSense':(
    pill6(*P(.085,.10),0.13*W,0.08*H,'#24252a','L1')+trig6(*P(.085,.215),0.13*W,0.09*H,'#24252a','L2','L')
   +pill6(*P(.915,.10),0.13*W,0.08*H,'#24252a','R1')+trig6(*P(.915,.215),0.13*W,0.09*H,'#24252a','R2','R')
   +pill6(*P(.5,.075),0.22*W,0.075*H,'#ECEDF1','TOUCHPAD','#55565e',0.03*H)+pill6(*P(.335,.075),0.075*W,0.055*H,'#3a3b42','CREATE',ls=0.022*H)+pill6(*P(.665,.075),0.075*W,0.055*H,'#3a3b42','OPTIONS',ls=0.021*H)
   +dpad6(*P(.26,.46),0.10*H,'#3a3b42')+stick6(*P(.12,.72),0.15*H,'#24252a')
   +psb(*P(.865,.395),0.065*H,'t','#5FD4B0')+psb(*P(.925,.52),0.065*H,'o','#F26B78')+psb(*P(.805,.52),0.065*H,'s','#E99BDC')+psb(*P(.865,.645),0.065*H,'x','#8FA8F5')
   +stick6(*P(.76,.80),0.13*H,'#24252a')+pill6(*P(.5,.93),0.08*W,0.055*H,'#3a3b42','MENU',ls=0.024*H),
   'DualSense: black and white, the four PlayStation shapes in their colours, L1 L2 R1 R2, sticks, touchpad, Create and Options'),
 'Xbox':(
    pill6(*P(.085,.10),0.13*W,0.08*H,'#2a2b30','LB')+trig6(*P(.085,.215),0.13*W,0.09*H,'#2a2b30','LT','L')
   +pill6(*P(.915,.10),0.13*W,0.08*H,'#2a2b30','RB')+trig6(*P(.915,.215),0.13*W,0.09*H,'#2a2b30','RT','R')
   +icon_btn(*P(.43,.075),0.036*H,'#2a2b30','view')+pill6(*P(.5,.075),0.08*W,0.055*H,'#3a3b42','MENU',ls=0.024*H)+icon_btn(*P(.57,.075),0.036*H,'#2a2b30','menu')
   +stick6(*P(.12,.60),0.15*H,'#2a2b30')+dpad6(*P(.26,.82),0.10*H,'#2a2b30')
   +btn6(*P(.865,.395),0.065*H,'#E2B02C','Y')+btn6(*P(.925,.52),0.065*H,'#D23B33','B')+btn6(*P(.805,.52),0.065*H,'#2E6ED6','X')+btn6(*P(.865,.645),0.065*H,'#3C9A3C','A')
   +stick6(*P(.76,.82),0.13*H,'#2a2b30'),
   'Xbox: black, A B X Y in their colours, LB LT RB RT, left stick above the d-pad, View and Menu'),
}
css='@font-face{font-family:Figtree;src:url(Figtree.ttf);font-weight:100 900}@font-face{font-family:Unbounded;src:url(Unbounded.ttf);font-weight:100 900}html,body{margin:0;overflow:hidden;background:#000}'
for n,(b,cp) in styles.items():
    open(f'v6_{n}.html','w').write(f'<html><head><style>{css}</style></head><body>{frame16(b,cp)}</body></html>')
