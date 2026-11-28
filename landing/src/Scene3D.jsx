import React from "react"
import { Canvas, useFrame } from "@react-three/fiber"
import { Float, Sparkles } from "@react-three/drei"
import { EffectComposer, Bloom, Vignette } from "@react-three/postprocessing"
import * as THREE from "three"

const LAYERS = 72
const HEIGHT = 2.7
const CYCLE = 9 // seconds per print loop

function starShape() {
  const spikes = 6
  const outer = 1.12
  const inner = 0.52
  const shape = new THREE.Shape()
  for (let i = 0; i < spikes * 2; i++) {
    const r = i % 2 === 0 ? outer : inner
    const a = (i / (spikes * 2)) * Math.PI * 2 - Math.PI / 2
    const x = Math.cos(a) * r
    const y = Math.sin(a) * r
    if (i === 0) shape.moveTo(x, y)
    else shape.lineTo(x, y)
  }
  shape.closePath()
  return shape
}

const EMBER = new THREE.Color("#f59e2e")
const CYAN = new THREE.Color("#4fd8ff")
const CREAM = new THREE.Color("#f3eadf")

/** A star-shaped model being "printed" layer by layer, looping. */
function PrintedStar({ headRef }) {
  const mesh = React.useRef()
  const dummy = React.useMemo(() => new THREE.Object3D(), [])
  const geometry = React.useMemo(() => new THREE.ShapeGeometry(starShape()), [])
  const material = React.useMemo(
    () =>
      new THREE.MeshStandardMaterial({
        metalness: 0.35,
        roughness: 0.32,
        emissive: new THREE.Color("#2a1503"),
        emissiveIntensity: 0.22,
        transparent: true,
      }),
    [],
  )

  // per-layer gradient color, set once
  React.useLayoutEffect(() => {
    const c = new THREE.Color()
    for (let i = 0; i < LAYERS; i++) {
      c.copy(EMBER).lerp(CYAN, i / (LAYERS - 1))
      mesh.current.setColorAt(i, c)
    }
    mesh.current.instanceColor.needsUpdate = true
  }, [])

  useFrame(({ clock }) => {
    if (!mesh.current) return
    const t = clock.getElapsedTime()
    const p = (t % CYCLE) / CYCLE
    // print phase 0→0.78, hold 0.78→0.9, fade 0.9→1
    const print = Math.min(p / 0.78, 1)
    const fade = p > 0.9 ? 1 - (p - 0.9) / 0.1 : 1
    const spin = t * 0.12

    for (let i = 0; i < LAYERS; i++) {
      const f = i / (LAYERS - 1)
      const s = THREE.MathUtils.clamp((print - f) / 0.04, 0, 1)
      const pop = s * (1 + 0.2 * Math.sin(Math.PI * s))
      dummy.position.set(0, -HEIGHT / 2 + f * HEIGHT, 0)
      dummy.rotation.set(-Math.PI / 2, 0, f * 0.28 + spin)
      const sc = pop * fade
      dummy.scale.set(sc, sc, sc)
      dummy.updateMatrix()
      mesh.current.setMatrixAt(i, dummy.matrix)
    }
    mesh.current.instanceMatrix.needsUpdate = true
    material.opacity = fade

    if (headRef.current) {
      const y = -HEIGHT / 2 + print * HEIGHT
      headRef.current.position.y = y
      headRef.current.visible = print < 0.999 && fade > 0.5
      const hs = 1 + Math.sin(t * 6) * 0.06
      headRef.current.scale.set(hs, hs, hs)
      headRef.current.rotation.y = t * 2.2
    }
  })

  return (
    <instancedMesh ref={mesh} args={[geometry, material, LAYERS]} frustumCulled={false} />
  )
}

/** Glowing nozzle ring that rides the top of the print. */
function PrintHead({ headRef }) {
  return (
    <group ref={headRef}>
      <mesh rotation={[Math.PI / 2, 0, 0]}>
        <torusGeometry args={[1.32, 0.028, 16, 96]} />
        <meshBasicMaterial color="#4fd8ff" toneMapped={false} />
      </mesh>
      <mesh rotation={[Math.PI / 2, 0, 0]}>
        <torusGeometry args={[1.32, 0.09, 16, 96]} />
        <meshBasicMaterial color="#1d6f8c" transparent opacity={0.18} toneMapped={false} />
      </mesh>
      <pointLight color="#4fd8ff" intensity={4} distance={4} decay={2} />
    </group>
  )
}

/** Wireframe of the full model, shown while layers fill in. */
function TargetShell() {
  const geometry = React.useMemo(() => {
    const extruded = new THREE.ExtrudeGeometry(starShape(), {
      depth: HEIGHT,
      bevelEnabled: false,
    })
    const edges = new THREE.EdgesGeometry(extruded)
    extruded.dispose()
    return edges
  }, [])
  return (
    <lineSegments geometry={geometry} position={[0, -HEIGHT / 2, 0]} rotation={[-Math.PI / 2, 0, 0]}>
      <lineBasicMaterial color={CREAM} transparent opacity={0.14} />
    </lineSegments>
  )
}

function BuildPlate() {
  return (
    <group position={[0, -HEIGHT / 2 - 0.09, 0]}>
      <mesh>
        <cylinderGeometry args={[2.05, 2.15, 0.14, 64]} />
        <meshStandardMaterial color="#1a1a1c" metalness={0.6} roughness={0.45} />
      </mesh>
      <mesh position={[0, 0.075, 0]} rotation={[-Math.PI / 2, 0, 0]}>
        <ringGeometry args={[1.7, 1.78, 64]} />
        <meshBasicMaterial color="#f59e2e" transparent opacity={0.35} toneMapped={false} />
      </mesh>
    </group>
  )
}

/** Gentle camera parallax following the pointer. */
/** Gentle camera parallax; on wide screens the print is framed right of the copy. */
function Rig() {
  useFrame(({ camera, pointer, viewport }) => {
    const shift = Math.min(1.7, Math.max(0, viewport.width * 0.5 - 3.4))
    camera.position.x += (pointer.x * 0.5 - shift - camera.position.x) * 0.04
    camera.position.y += (2.3 + pointer.y * 0.3 - camera.position.y) * 0.04
    camera.lookAt(-shift, -0.35, 0)
  })
  return null
}

export default function Scene3D() {
  const headRef = React.useRef()
  return (
    <Canvas
      camera={{ position: [0, 1.1, 6.3], fov: 42 }}
      dpr={[1, 1.6]}
      gl={{ antialias: true, alpha: true }}
      style={{ position: "absolute", inset: 0 }}
    >
      <ambientLight intensity={0.3} />
      <directionalLight position={[4, 6, 3]} intensity={1.2} color="#ffe6c4" />
      <pointLight position={[-4, 2, -2]} intensity={9} color="#4fd8ff" decay={2} />
      <pointLight position={[3, -1, 3]} intensity={5} color="#f59e2e" decay={2} />

      <Float speed={1.4} rotationIntensity={0.25} floatIntensity={0.35}>
        <TargetShell />
        <PrintedStar headRef={headRef} />
        <PrintHead headRef={headRef} />
        <BuildPlate />
      </Float>

      <Sparkles count={70} scale={[7, 4.5, 4]} size={2.2} speed={0.25} opacity={0.35} color="#ffd08a" />
      <Rig />

      <EffectComposer multisampling={0}>
        <Bloom mipmapBlur luminanceThreshold={1.0} intensity={0.45} radius={0.6} />
        <Vignette darkness={0.6} offset={0.25} />
      </EffectComposer>
    </Canvas>
  )
}
