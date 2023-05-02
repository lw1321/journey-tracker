const map = L.map('map');

let mapBounds = window.localStorage.getItem('mapBounds');
if (mapBounds) {
    [west, south, east, north] = mapBounds.split(',').map(parseFloat)
    let bounds = new L.LatLngBounds(new L.LatLng(south, west), new L.LatLng(north, east))
    map.fitBounds(bounds);
} else {
    map.setView([49.505, 10], 5);
}
map.on('moveend', function (e) {
    let bounds = map.getBounds();
    window.localStorage.setItem('mapBounds', bounds.toBBoxString());
});

let imageWidth = '100%'; // set the width of the image popup
let imageHeight = '100%'; // set the height of the image popup

let bikeIcon = L.icon({
    iconUrl: 'bike.png',
    iconSize: [30, 17], // size of the icon
    iconAnchor: [12, 25], // point of the icon which will correspond to marker's location
    popupAnchor: [-3, -76] // point from which the popup should open relative to the iconAnchor
});

L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
    maxZoom: 19,
    attribution: '&copy; <a href="http://www.openstreetmap.org/copyright">OpenStreetMap</a>'
}).addTo(map);


fetch('https://tellmemore.dev/v1/wahoo-geojson')
    .then(response => response.json())
    .then(data => {

        let colorPalette = [
            '#f44336',
            'black',
            '#2196f3',
            '#ffeb3b',
            '#ff9800',
            '#9c27b0',
            '#00bcd4',
            '#e91e63',
            '#009688',
            '#e91e63',
            '#9c27b0',
            '#cddc39',
            '#ff5722'
        ];

        for (let i = 0; i < data.length; i++) {
            let wahooRecord = data[i];
            let array = JSON.parse(wahooRecord.route)
            let route = [{
                "type": "LineString",
                "coordinates": array
            }];

            let colorIndex = i % colorPalette.length; // determine the color index based on the number of days
            let color = colorPalette[colorIndex]; // get the color from the palette

            let myStyle = {
                "color": color,
                "weight": 6,
                "opacity": 0.75
            };

            L.geoJSON(route, {
                style: myStyle
            }).addTo(map);
        }

    })
    .catch(error => {
        console.error(error);
        // handle the error
    });


fetch('https://tellmemore.dev/v1/location-images')
    .then(response => response.json())
    .then(data => {
     var markers = L.markerClusterGroup().addTo(map);

        // do something with the data
        for (let i = 0; i < data.length; i++) {
            let imageLocation = data[i];

            let imageIcon = L.icon({
                iconUrl: imageLocation.thumbUrl,
                iconSize: [30, 30], // size of the icon
                iconAnchor: [15, 15], // point of the icon which will correspond to marker's location
                popupAnchor: [-3, -12] // point from which the popup should open relative to the iconAnchor
            });
            let marker = L.marker([imageLocation.latitude, imageLocation.longitude], {
                icon: imageIcon
            });
            let imageDate = new Date(imageLocation.createdDate * 1000);
            let comment = (imageLocation.comment === "null" || imageLocation.comment === "0") ? "" : imageLocation.comment;
            let author = (!imageLocation.author || imageLocation.author === "null" || imageLocation.author === "0") ? "" : " von <i>" + imageLocation.author + "</i>";
            let formattedDate = imageDate.toLocaleDateString('de-DE'); //imageDate.toLocaleTimeString('de-DE'); // Format as string
            let popupContent = '<div>' +
                '<a href="' + imageLocation.imageUrl + '" target="_blank">' +
                '<img src="' + imageLocation.thumbUrl + '" width="' + imageWidth + '" height="' + imageHeight + '"></div>' +
                '</a>' +
                '<div>' + formattedDate + author + "</br>  " + comment + "</div>" +
                '<div><a href="https://www.google.de/maps/place/' + imageLocation.latitude + '+' + imageLocation.longitude + '/@' + imageLocation.latitude + '+' + imageLocation.longitude + ',15z" target="_blank">Öffnen</a></div>' +
                '</div>';

            marker.bindPopup(popupContent);
            markers.addLayer(marker);
        }

    })
    .catch(error => {
        console.error(error);
        // handle the error
    });
