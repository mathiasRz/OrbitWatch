import { SatellitePosition } from '../models/satellite-position.model';

/**
 * Découpe un ground track en segments de polyline pour Leaflet,
 * en coupant proprement à chaque franchissement de l'anti-méridien (±180°).
 *
 * Algorithme :
 *  1. Construire une longitude « continue » (unwrapped) pour suivre le vrai
 *     mouvement du satellite sans saut artificiel.
 *  2. À chaque fois que la longitude continue franchit ±180°, interpoler
 *     le point exact sur le bord (lat_bord, ±180) et terminer/commencer
 *     un nouveau segment.
 *  3. Chaque segment a ses longitudes clampées dans [-180, 180].
 */
export function splitAtAntimeridian(track: SatellitePosition[]): [number, number][][] {
  if (track.length === 0) return [];

  const norm = (lon: number) => ((lon % 360) + 540) % 360 - 180;

  // 1. Construire le chemin continu (longitude unwrapped)
  const pts: { lat: number; lon: number }[] = [];
  let prevRaw = norm(track[0].longitude);
  pts.push({ lat: track[0].latitude, lon: prevRaw });

  for (let i = 1; i < track.length; i++) {
    const rawLon = norm(track[i].longitude);
    // Delta le plus court (sur la sphère)
    let delta = rawLon - norm(prevRaw);
    if (delta > 180) delta -= 360;
    if (delta < -180) delta += 360;
    const contLon = prevRaw + delta;  // longitude continue (peut dépasser ±180)
    pts.push({ lat: track[i].latitude, lon: contLon });
    prevRaw = contLon;
  }

  // 2. Découper aux franchissements de ±180
  const segments: [number, number][][] = [];
  let cur: [number, number][] = [clamp(pts[0])];

  for (let i = 1; i < pts.length; i++) {
    const p0 = pts[i - 1];
    const p1 = pts[i];

    // Vérifie si on a traversé la frontière ±180
    const crossings = getCrossings(p0.lon, p1.lon);
    if (crossings.length === 0) {
      // Pas de franchissement — on ajoute directement
      cur.push(clamp(p1));
    } else {
      // Franchissement(s) — on coupe à chaque passage
      for (const boundary of crossings) {
        // Fraction linéaire du passage
        const t = (boundary - p0.lon) / (p1.lon - p0.lon);
        const midLat = p0.lat + t * (p1.lat - p0.lat);

        // Termine le segment courant au bord
        cur.push([midLat, boundary > 0 ? 180 : -180]);
        if (cur.length > 1) segments.push(cur);

        // Commence un nouveau segment de l'autre côté
        cur = [[midLat, boundary > 0 ? -180 : 180]];
      }
      cur.push(clamp(p1));
    }
  }
  if (cur.length > 1) segments.push(cur);

  return segments;
}

/** Ramène la longitude continue dans [-180, 180] pour l'affichage Leaflet. */
function clamp(p: { lat: number; lon: number }): [number, number] {
  const lon = ((p.lon % 360) + 540) % 360 - 180;
  return [p.lat, lon];
}

/**
 * Retourne les valeurs de frontière (±180, ±540, …) traversées
 * entre lon0 et lon1 (longitudes continues, pouvant dépasser ±180).
 */
function getCrossings(lon0: number, lon1: number): number[] {
  const lo = Math.min(lon0, lon1);
  const hi = Math.max(lon0, lon1);
  const crossings: number[] = [];

  // On cherche tous les multiples impairs de 180 (±180, ±540, ±900…)
  // entre lo et hi (exclus les multiples pairs = ±0, ±360…)
  const start = Math.ceil(lo / 180);
  const end   = Math.floor(hi / 180);
  for (let k = start; k <= end; k++) {
    if (k !== 0 && k % 2 !== 0) {
      const val = k * 180;
      if (val > lo && val < hi) {
        crossings.push(val);
      }
    }
  }

  // Trier dans l'ordre du mouvement
  if (lon0 > lon1) crossings.reverse();
  return crossings;
}

