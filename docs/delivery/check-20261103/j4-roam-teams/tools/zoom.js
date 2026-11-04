(s => { const p = getCurrentPages().slice(-1)[0]; const lat = 31.2304, lng = 121.4737;
  const mpp = 156543.03 * Math.cos(lat*Math.PI/180) / Math.pow(2, s);
  const dLat = 844*mpp/111000/2, dLng = 390*mpp/(111000*Math.cos(lat*Math.PI/180))/2;
  p.setData({ mapScale: s });
  p.onRegionChange({ detail: { type: 'end', causedBy: 'scale', scale: s, region: { southwest: { latitude: lat-dLat, longitude: lng-dLng }, northeast: { latitude: lat+dLat, longitude: lng+dLng } } } });
  return 'zoom ' + s; })
