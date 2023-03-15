const map = L.map('map').setView([49.505, 10], 5);

let imageWidth = '100%'; // set the width of the image popup
let imageHeight = '100%'; // set the height of the image popup

let bikeIcon = L.icon({
    iconUrl: 'bike.png',
    iconSize: [40, 22], // size of the icon
    iconAnchor: [12, 25], // point of the icon which will correspond to marker's location
    popupAnchor: [-3, -76] // point from which the popup should open relative to the iconAnchor
});

L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
    maxZoom: 19,
    attribution: '&copy; <a href="http://www.openstreetmap.org/copyright">OpenStreetMap</a>'
}).addTo(map);

fetch('https://tellmemore.dev/v1/locations')
    .then(response => response.json())
    .then(data => {
        // do something with the data
        for (let i = 0; i < data.length; i++) {
            let location = data[i];
            L.marker([location.latitude, location.longitude], {
                icon: bikeIcon,
                zIndexOffset: 1000,
            }).addTo(map);
        }
    })
    .catch(error => {
        console.error(error);
        // handle the error
    });


fetch('https://tellmemore.dev/v1/wahoo-geojson')
    .then(response => response.json())
    .then(data => {
        // do something with the data
        for (let i = 0; i < data.length; i++) {
            let wahooRecord = data[i];
            let array = JSON.parse(wahooRecord.route)
            let route = [{
                "type": "LineString",
                "coordinates": array
            }];
            let myStyle = {
                "color": "#DC143C",
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
        // do something with the data
        for (let i = 0; i < data.length; i++) {
            let imageLocation = data[i];

            let imageIcon = L.icon({
                iconUrl: imageLocation.thumbUrl,
                iconSize: [30, 30], // size of the icon
                iconAnchor: [25, 25], // point of the icon which will correspond to marker's location
                popupAnchor: [-3, -76] // point from which the popup should open relative to the iconAnchor
            });
            let marker = L.marker([imageLocation.latitude, imageLocation.longitude], {
                icon: imageIcon
            }).addTo(map);
            let imageDate = new Date(imageLocation.createdDate * 1000);
            let comment = (imageLocation.comment == "null" || "0") ? "" : imageLocation.comment;
            let formattedDate = imageDate.toLocaleDateString('de-DE'); //imageDate.toLocaleTimeString('de-DE'); // Format as string
            let popupContent = '<div><img src="' + imageLocation.thumbUrl + '" width="' + imageWidth + '" height="' + imageHeight + '"></div><div>' + formattedDate + "   " + comment + '</div>';

            marker.bindPopup(popupContent).openPopup();
        }

    })
    .catch(error => {
        console.error(error);
        // handle the error
    });
