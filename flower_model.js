// 꽃 3D 모델: 꽃잎 모양이 다른 4종 (flower1.png / flower2.png 참고)
// 부품: 꽃잎 n장, 수술 6개, 꽃받침 1개. 종류마다 꽃잎 수·폭·끝 모양·젖힘·오목함·피는 각도가 다름.
// 여러 송이를 InstancedMesh로 한 번에 그린다 → 드로우콜은 종류 수 + 2(수술·꽃받침)뿐.
//
// 사용:
//   const lilies = createLilies(THREE, mergeGeometries, 송이수);
//   scene.add(lilies.group);
//   lilies.set(i, matrix, open, color, kind);   // open: 0 = 닫힌 봉오리, 1 = 활짝, kind: FLOWER_KINDS 번호
//   lilies.commit();                            // set 후 한 번
// 꽃의 축은 로컬 +Y (꽃이 바라보는 방향). matrix로 위치·방향·크기를 준다. 백합 꽃잎 길이 = 1.
//
// file://로 열어도 되도록 모듈이 아닌 일반 스크립트. THREE는 인자로 받는다.

// n: 꽃잎 수, alt: 안쪽·바깥쪽 꽃잎이 번갈아 (안쪽은 조금 좁고 덜 핌), len: 꽃잎 길이,
// w: 최대 폭, a·b: 폭 모양 (a가 클수록 폭이 끝 쪽에서 최대, b가 작을수록 끝이 둥긂),
// notch: 끝 가운데 홈, recurve: 끝이 뒤로 젖혀지는 정도(음수면 안으로 오므림), cup: 폭 방향 오목함,
// open: 활짝 폈을 때 꽃잎 각도, stamen: 수술 크기,
// throat: 밑동 색(목), vein: 가운데 줄 진하기, base: 밑동 쪽 어둡기
window.FLOWER_KINDS = [
  {name: '백합', n: 6, alt: true, len: 1, w: 0.17, a: 0.8, b: 0.85, notch: 0, recurve: 0.38, cup: 0.05, open: 1.2, stamen: 1,
   throat: [0.92, 1.0, 0.8], vein: 0.22, base: 0},
  {name: '코스모스', n: 8, alt: false, len: 0.78, w: 0.21, a: 1.6, b: 0.45, notch: 0.1, recurve: 0.06, cup: 0.03, open: 1.42, stamen: 0.55,
   throat: [1.0, 0.86, 0.45], vein: 0.1, base: 0.25},
  {name: '별꽃', n: 5, alt: false, len: 0.9, w: 0.25, a: 0.75, b: 0.65, notch: 0, recurve: 0.14, cup: 0.04, open: 1.3, stamen: 0.8,
   throat: [1.0, 0.95, 0.85], vein: 0.18, base: 0.1},
  {name: '목련', n: 6, alt: true, len: 0.85, w: 0.25, a: 1.2, b: 0.5, notch: 0, recurve: 0.02, cup: 0.1, open: 0.95, stamen: 0.5,
   throat: [0.9, 0.7, 0.8], vein: 0.06, base: 0.15},
];

window.createLilies = function (THREE, mergeGeometries, count) {
  const KINDS = window.FLOWER_KINDS;

  // 꽃잎: 길이 +Y, 폭 X. 꽃 앞(보는 쪽)은 -Z 면.
  function petalGeometry(K) {
    const nu = 28, nv = 12, pos = [], col = [], idx = []; // 코스모스 끝 홈이 매끄럽게 보일 만큼 촘촘히
    for (let i = 0; i <= nu; i++) {
      const u = i / nu;
      const w = K.w * Math.pow(Math.sin(Math.PI * Math.pow(u, K.a)), K.b);
      for (let j = 0; j <= nv; j++) {
        const v = j / nv * 2 - 1, a = Math.abs(v);
        const y = K.len * u * (1 - K.notch * Math.exp(-v * v / 0.08) * Math.pow(u, 10)); // 끝 가운데 홈
        pos.push(v * w, y, K.recurve * u * u * u - K.cup * v * v * (w / K.w) + 0.012 * Math.sin(u * 9 + v * 2));
        // 밝기 패턴 (색조는 인스턴스 색이 곱해짐): 가운데 줄은 진하게, 가장자리·끝은 밝게, 밑동은 종류별 목 색
        let r = (1 - K.vein) + K.vein * Math.pow(a, 0.7);
        r = Math.min(1, r + 0.1 * u) * (1 - K.base * Math.max(0, 1 - u / 0.35)); // 밑동 쪽을 깊은 색으로
        const t = Math.max(0, 1 - u / 0.18); // 목
        col.push(r * (1 - t) + K.throat[0] * t, r * (1 - t) + K.throat[1] * t, r * (1 - t) + K.throat[2] * t);
        if (i < nu && j < nv) { const p = i * (nv + 1) + j, q = p + nv + 1; idx.push(p, p + 1, q, q, p + 1, q + 1); }
      }
    }
    const geo = new THREE.BufferGeometry();
    geo.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
    geo.setAttribute('color', new THREE.Float32BufferAttribute(col, 3));
    geo.setIndex(idx); geo.computeVertexNormals();
    return geo;
  }

  // 수술: 살짝 휜 가는 꽃실 + 끝의 진한 꽃밥
  function stamenGeometry() {
    const V = THREE.Vector3;
    const fil = new THREE.TubeGeometry(new THREE.QuadraticBezierCurve3(new V(0, 0, 0), new V(0, 0.32, -0.02), new V(0, 0.55, 0.1)), 12, 0.006, 5);
    const anther = new THREE.SphereGeometry(0.026, 8, 6);
    anther.scale(0.7, 1.4, 0.7); anther.translate(0, 0.56, 0.11);
    const paint = (g, hex) => {
      const c = new THREE.Color(hex), a = new Float32Array(g.attributes.position.count * 3);
      for (let i = 0; i < a.length; i += 3) { a[i] = c.r; a[i + 1] = c.g; a[i + 2] = c.b; }
      g.setAttribute('color', new THREE.BufferAttribute(a, 3));
      g.deleteAttribute('uv');
      return g;
    };
    return mergeGeometries([paint(fil, '#d6e2a8'), paint(anther, '#7a3a14')]);
  }

  const petalMat = new THREE.MeshPhysicalMaterial({ vertexColors: true, side: THREE.DoubleSide, roughness: 0.55, sheen: 0.35, sheenColor: new THREE.Color('#ffffff') }); // sheen이 강하면 색이 하얗게 바램
  const stamenMat = new THREE.MeshStandardMaterial({ vertexColors: true, roughness: 0.6 });
  const baseGeo = new THREE.SphereGeometry(0.07, 12, 8); baseGeo.scale(1, 1.5, 1);
  const baseMat = new THREE.MeshStandardMaterial({ color: '#6f8f3a', roughness: 0.6 });

  const petals = KINDS.map(K => new THREE.InstancedMesh(petalGeometry(K), petalMat, count * K.n));
  const stamens = new THREE.InstancedMesh(stamenGeometry(), stamenMat, count * 6);
  const bases = new THREE.InstancedMesh(baseGeo, baseMat, count);
  const group = new THREE.Group();
  group.add(...petals, stamens, bases);
  for (const m of [...petals, stamens, bases]) m.frustumCulled = false; // 인스턴스가 흩어져 있어 기본 바운딩으로 컬링하면 안 됨

  const Ry = new THREE.Matrix4(), Rx = new THREE.Matrix4(), Sc = new THREE.Matrix4(), M = new THREE.Matrix4();
  const ZERO = new THREE.Matrix4().makeScale(0, 0, 0), white = new THREE.Color(1, 1, 1);
  const kindOf = new Array(count).fill(-1);

  function set(i, matrix, open, color, kind = 0) {
    if (kindOf[i] !== kind && kindOf[i] >= 0) // 종류가 바뀌면 이전 종류의 꽃잎을 숨김
      for (let k = 0; k < KINDS[kindOf[i]].n; k++) petals[kindOf[i]].setMatrixAt(i * KINDS[kindOf[i]].n + k, ZERO);
    kindOf[i] = kind;
    const K = KINDS[kind], P = petals[kind];
    const tilt = 0.1 + (K.open - 0.1) * open; // 봉오리: 꽃잎이 축에 모여 닫힘 → 활짝: 종류별 각도
    for (let k = 0; k < K.n; k++) {
      const inner = K.alt && k % 2 === 1;
      Ry.makeRotationY(k * Math.PI * 2 / K.n);
      Rx.makeRotationX(tilt * (inner ? 0.9 : 1));
      Sc.makeScale(inner ? 0.9 : 1, 1, 1); // 안쪽 꽃잎은 조금 좁게
      M.copy(matrix).multiply(Ry).multiply(Rx).multiply(Sc);
      P.setMatrixAt(i * K.n + k, M);
      P.setColorAt(i * K.n + k, color);
    }
    for (let k = 0; k < 6; k++) {
      Ry.makeRotationY(k * Math.PI / 3 + Math.PI / 6);
      Rx.makeRotationX(0.15 + 0.3 * open);
      Sc.makeScale(open * K.stamen, open * K.stamen, open * K.stamen); // 봉오리일 땐 수술이 안 보이게
      M.copy(matrix).multiply(Ry).multiply(Rx).multiply(Sc);
      stamens.setMatrixAt(i * 6 + k, M);
    }
    bases.setMatrixAt(i, matrix);
  }

  function commit() {
    for (const P of petals) {
      P.instanceMatrix.needsUpdate = true;
      if (P.instanceColor) P.instanceColor.needsUpdate = true;
    }
    stamens.instanceMatrix.needsUpdate = bases.instanceMatrix.needsUpdate = true;
  }

  for (let i = 0; i < count; i++) {
    for (let k = 0; k < KINDS.length; k++) for (let j = 0; j < KINDS[k].n; j++) { petals[k].setMatrixAt(i * KINDS[k].n + j, ZERO); petals[k].setColorAt(i * KINDS[k].n + j, white); }
    for (let k = 0; k < 6; k++) stamens.setMatrixAt(i * 6 + k, ZERO);
    bases.setMatrixAt(i, ZERO);
  }
  commit();
  return { group, set, commit };
};
