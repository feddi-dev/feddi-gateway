FROM node:20-alpine

WORKDIR /app

COPY package.json ./
RUN npm install

COPY index.js ./

EXPOSE 4001

CMD ["npm", "start"]
